package org.lytharalab.gfbs.auralis;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.auralis.api.AuralisSoundEvent;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import org.lytharalab.gfbs.auralis.api.AuralisSoundListener;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;
import org.lytharalab.gfbs.auralis.utils.OggVorbisDecoder;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One logical Auralis sound instance.
 *
 * <p>Since 2.1.0 the logical playback clock is authoritative. A physical
 * OpenAL source can be attached and detached at any time without stopping the
 * logical voice. This lets thousands of instances keep correct playback state
 * while only the audible/high-priority subset consumes OpenAL sources.</p>
 */
final class AuralisSoundInstanceImpl implements AuralisSoundInstance {
    private static final int STREAM_BUFFER_COUNT = 4;
    private static final double NANOS_TO_SECONDS = 1.0 / 1_000_000_000.0;

    private final AuralisAL al;
    private final int alBuffer;

    // Streaming state. The decoder exists for the logical instance; OpenAL
    // streaming buffers are created lazily only after the voice becomes physical.
    private final @Nullable OggVorbisDecoder.StreamDecoder streamDecoder;
    private volatile @Nullable int[] streamingBuffers;
    private final @Nullable ByteBuffer decodeBuffer;
    private final double durationSeconds;

    private final SoundBufferCache bufferCache;
    private final OpenALSourcePool sourcePool;

    private volatile float volume = 1.0f;
    private volatile float smoothedVolume = 1.0f;
    private volatile float pitch = 1.0f;
    private volatile float speed = 1.0f;

    private volatile boolean isStatic = false;
    private volatile boolean looping = false;
    private volatile boolean autoDisposeOnFinish = true;
    private final boolean isStreamed;

    private volatile Vec3 position = Vec3.ZERO;
    private volatile float minDistance = 1.0f;
    private volatile float maxDistance = 48.0f;
    private volatile int priority = 50;

    private volatile @Nullable OpenALSourcePool.SourceHandle source;
    private final Object sourceLifecycleLock = new Object();

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean startedPlayback = new AtomicBoolean(false);
    private final AtomicBoolean bindingRequested = new AtomicBoolean(false);
    private final AtomicBoolean pendingNaturalCompletion = new AtomicBoolean(false);
    private final AtomicBoolean pendingCompletionWasPhysical = new AtomicBoolean(false);
    private final AtomicBoolean pendingEngineRemoval = new AtomicBoolean(false);
    private final AtomicBoolean resourcesFreed = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    private final Set<AuralisSoundListener> listeners = new CopyOnWriteArraySet<>();
    private final List<AudioProcessor> processorChain = new CopyOnWriteArrayList<>();

    // Authoritative logical playback cursor, measured in source-media seconds.
    private final Object playbackClockLock = new Object();
    private volatile double logicalPlaybackSeconds = 0.0;
    private volatile long logicalClockNanos = System.nanoTime();

    // Static-buffer constructor.
    AuralisSoundInstanceImpl(AuralisAL al, int alBuffer, SoundBufferCache bufferCache, OpenALSourcePool sourcePool) {
        this.al = Objects.requireNonNull(al, "al");
        this.alBuffer = alBuffer;
        this.streamDecoder = null;
        this.streamingBuffers = null;
        this.decodeBuffer = null;
        this.bufferCache = Objects.requireNonNull(bufferCache, "bufferCache");
        this.sourcePool = Objects.requireNonNull(sourcePool, "sourcePool");
        this.isStreamed = false;
        this.durationSeconds = bufferCache.getBufferDurationSeconds(alBuffer);
    }

    // Streamed constructor. Streaming OpenAL buffers are intentionally lazy.
    AuralisSoundInstanceImpl(
            AuralisAL al,
            OggVorbisDecoder.StreamDecoder streamDecoder,
            int chunkSize,
            SoundBufferCache bufferCache,
            OpenALSourcePool sourcePool
    ) {
        this.al = Objects.requireNonNull(al, "al");
        this.alBuffer = -1;
        this.streamDecoder = Objects.requireNonNull(streamDecoder, "streamDecoder");
        this.streamingBuffers = null;
        this.decodeBuffer = MemoryUtil.memAlloc(chunkSize);
        this.bufferCache = Objects.requireNonNull(bufferCache, "bufferCache");
        this.sourcePool = Objects.requireNonNull(sourcePool, "sourcePool");
        this.isStreamed = true;
        double reportedDuration = streamDecoder.getDurationSeconds();
        this.durationSeconds = Double.isFinite(reportedDuration) && reportedDuration > 0.0
                ? reportedDuration
                : 0.0;
    }

    @Override
    public boolean isBound() {
        return source != null;
    }

    /**
     * Request participation in physical playback. In 2.1 this no longer means
     * "allocate a Source right now"; the voice manager decides whether the
     * logical voice should currently be physical or virtual.
     */
    void bind() {
        if (disposed.get() || resourcesFreed.get()) return;
        if (!isStreamed && alBuffer == -1) return;
        bindingRequested.set(true);
    }

    @Override
    public void play() {
        if (disposed.get() || resourcesFreed.get()) return;
        if (!isStreamed && alBuffer == -1) return;
        if (isStreamed && streamDecoder == null) return;

        long now = System.nanoTime();
        boolean shouldRestartPhysical;
        synchronized (playbackClockLock) {
            boolean wasRunning = startedPlayback.get() && !paused.get();
            if (startedPlayback.get()) {
                syncLogicalClockLocked(now);
            }

            if (!startedPlayback.get()) {
                // Replaying a retained one-shot starts from the beginning.
                if (!looping && durationSeconds > 0.0 && logicalPlaybackSeconds >= durationSeconds) {
                    logicalPlaybackSeconds = 0.0;
                }
                startedPlayback.set(true);
            }

            paused.set(false);
            logicalClockNanos = now;
            pendingNaturalCompletion.set(false);
            shouldRestartPhysical = !wasRunning;
        }

        // play() historically auto-requested a source even if bind() was omitted.
        bindingRequested.set(true);

        OpenALSourcePool.SourceHandle handle = source;
        if (shouldRestartPhysical && handle != null) {
            restartPhysicalPlaybackFromLogicalCursor(handle);
        }

        fireEvent(AuralisSoundEvent.PLAY);
    }

    @Override
    public void pause() {
        if (disposed.get() || resourcesFreed.get()) return;
        if (!startedPlayback.get() || paused.get()) return;

        syncLogicalClock(System.nanoTime());
        paused.set(true);

        OpenALSourcePool.SourceHandle handle = source;
        if (handle != null) {
            final int sourceId = handle.sourceId();
            submitALTask(() -> {
                if (source == handle) {
                    AL10.alSourcePause(sourceId);
                }
            });
        }

        fireEvent(AuralisSoundEvent.PAUSE);
    }

    @Override
    public void stop() {
        if (disposed.get() || resourcesFreed.get()) return;

        syncLogicalClock(System.nanoTime());
        synchronized (playbackClockLock) {
            logicalPlaybackSeconds = 0.0;
            logicalClockNanos = System.nanoTime();
            startedPlayback.set(false);
            paused.set(false);
            pendingNaturalCompletion.set(false);
        }

        // A stopped logical voice never needs a scarce physical source. Keep
        // STOP -> UNBIND event ordering compatible with natural completion.
        boolean hadPhysicalSource = source != null;
        releasePhysicalVoice(false);
        // Do not queue a decoder seek here. The logical cursor is already reset,
        // and the next materialization seeks to it before playback. The old queued
        // seek could run after unbind() closed the native STB handle, causing the
        // fatal stb_vorbis_seek_start use-after-free captured in the JVM crash log.

        fireEvent(AuralisSoundEvent.STOP);
        if (hadPhysicalSource) {
            fireEvent(AuralisSoundEvent.UNBIND);
        }
    }

    @Override
    public boolean isPlaying() {
        // Logical state, intentionally independent of whether an OpenAL source exists.
        return startedPlayback.get() && !paused.get() && !disposed.get() && !resourcesFreed.get();
    }

    @Override
    public boolean isPaused() {
        return startedPlayback.get() && paused.get();
    }

    @Override
    public double getPlaybackPositionSeconds() {
        syncLogicalClock(System.nanoTime());
        return logicalPlaybackSeconds;
    }

    @Override
    public double getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public AuralisSoundInstance setVolume(float volume) {
        float v = Float.isFinite(volume) ? volume : 0.0f;
        this.volume = Math.max(0.0f, v);
        if (source == null) {
            // Avoid a stale smoothing value causing a gain spike when materialized.
            this.smoothedVolume = this.volume;
        }
        // Gain is deliberately NOT pushed here. The voice manager/attenuation pass
        // applies gain using the current listener position, preventing one-frame
        // full-volume flashes for distant sounds.
        return this;
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @Override
    public AuralisSoundInstance setPitch(float pitch) {
        syncLogicalClock(System.nanoTime());
        float p = Float.isFinite(pitch) ? pitch : 1.0f;
        this.pitch = clamp(p, 0.01f, 8.0f);
        pushNonGainParamsIfBound();
        return this;
    }

    @Override
    public float getPitch() {
        return pitch;
    }

    @Override
    public AuralisSoundInstance setSpeed(float speed) {
        syncLogicalClock(System.nanoTime());
        float s = Float.isFinite(speed) ? speed : 1.0f;
        this.speed = clamp(s, 0.01f, 8.0f);
        pushNonGainParamsIfBound();
        return this;
    }

    @Override
    public float getSpeed() {
        return speed;
    }

    @Override
    public AuralisSoundInstance setStatic(boolean isStatic) {
        this.isStatic = isStatic;
        pushNonGainParamsIfBound();
        return this;
    }

    @Override
    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public AuralisSoundInstance setPosition(Vec3 pos) {
        Vec3 p = Objects.requireNonNull(pos, "pos");
        if (!isFinite(p)) p = Vec3.ZERO;
        this.position = p;
        pushNonGainParamsIfBound();
        return this;
    }

    @Override
    public Vec3 getPosition() {
        return position;
    }

    @Override
    public AuralisSoundInstance setMinDistance(float dist) {
        float d = Float.isFinite(dist) ? dist : 0.0f;
        this.minDistance = Math.max(0.0f, d);
        return this;
    }

    @Override
    public float getMinDistance() {
        return minDistance;
    }

    @Override
    public AuralisSoundInstance setMaxDistance(float dist) {
        float d = Float.isFinite(dist) ? dist : 0.0f;
        this.maxDistance = Math.max(0.0f, d);
        return this;
    }

    @Override
    public float getMaxDistance() {
        return maxDistance;
    }

    @Override
    public AuralisSoundInstance setLooping(boolean looping) {
        syncLogicalClock(System.nanoTime());
        this.looping = looping;

        OpenALSourcePool.SourceHandle handle = source;
        if (handle != null && !isStreamed) {
            final int sourceId = handle.sourceId();
            submitALTask(() -> {
                if (source == handle) {
                    AL10.alSourcei(sourceId, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
                }
            });
        }
        return this;
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    @Override
    public AuralisSoundInstance setAutoDisposeOnFinish(boolean enabled) {
        this.autoDisposeOnFinish = enabled;
        return this;
    }

    @Override
    public boolean isAutoDisposeOnFinish() {
        return autoDisposeOnFinish;
    }

    @Override
    public AuralisSoundInstance setPriority(int priority) {
        this.priority = clamp(priority, 0, 100);
        return this;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public AuralisSoundInstance addProcessor(AudioProcessor processor) {
        processorChain.add(Objects.requireNonNull(processor, "processor"));
        // Processor priority retains the existing "smaller value runs first" rule.
        processorChain.sort((p1, p2) -> Integer.compare(p1.getPriority(), p2.getPriority()));
        return this;
    }

    @Override
    public AuralisSoundInstance addListener(AuralisSoundListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    @Override
    public AuralisSoundInstance removeListener(AuralisSoundListener listener) {
        listeners.remove(listener);
        return this;
    }

    /* --------------------------------------------------------------------- */
    /* 2.1 logical voice / virtualization internals                          */
    /* --------------------------------------------------------------------- */

    boolean isPhysicalVoice() {
        return source != null;
    }

    boolean isBindingRequested() {
        return bindingRequested.get();
    }

    boolean isDisposed() {
        return disposed.get() || resourcesFreed.get();
    }

    boolean hasPlayableResource() {
        return isStreamed ? streamDecoder != null : alBuffer != -1;
    }

    boolean isLogicallyActive() {
        return startedPlayback.get() && !disposed.get() && !resourcesFreed.get();
    }

    boolean hasPendingNaturalCompletion() {
        return pendingNaturalCompletion.get();
    }

    /** Advance the authoritative clock even while the voice is virtual. */
    void advanceLogicalVoice(long nowNanos) {
        if (disposed.get() || resourcesFreed.get()) return;

        boolean completed = false;
        synchronized (playbackClockLock) {
            if (!startedPlayback.get() || paused.get()) {
                logicalClockNanos = nowNanos;
                return;
            }

            syncLogicalClockLocked(nowNanos);
            if (!looping && durationSeconds > 0.0 && logicalPlaybackSeconds >= durationSeconds) {
                logicalPlaybackSeconds = durationSeconds;
                startedPlayback.set(false);
                paused.set(false);
                pendingNaturalCompletion.set(true);
                completed = true;
            }
        }

        if (completed) {
            // Stop the physical renderer immediately; event delivery is finalized
            // after the voice-manager pass so STOP precedes UNBIND as in 2.0.
            pendingCompletionWasPhysical.set(source != null);
            releasePhysicalVoice(false);
        }
    }

    private void syncLogicalClock(long nowNanos) {
        synchronized (playbackClockLock) {
            syncLogicalClockLocked(nowNanos);
        }
    }

    private void syncLogicalClockLocked(long nowNanos) {
        long previous = logicalClockNanos;
        logicalClockNanos = nowNanos;
        if (!startedPlayback.get() || paused.get()) return;
        if (nowNanos <= previous) return;

        double elapsed = (nowNanos - previous) * NANOS_TO_SECONDS;
        double rate = clamp(pitch * speed, 0.01f, 8.0f);
        logicalPlaybackSeconds += elapsed * rate;

        if (looping && durationSeconds > 0.0) {
            logicalPlaybackSeconds %= durationSeconds;
            if (logicalPlaybackSeconds < 0.0) logicalPlaybackSeconds += durationSeconds;
        }
    }

    float estimateAudibleGain(Vec3 listenerPos, float attenuationExponent) {
        float baseVolume = Math.max(0.0f, volume);
        if (baseVolume <= 0.0f) return 0.0f;
        if (isStatic) return baseVolume;

        Vec3 src = position;
        double dx = src.x - listenerPos.x;
        double dy = src.y - listenerPos.y;
        double dz = src.z - listenerPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float factor = distanceFactor(distance);
        float exp = Math.max(0.0001f, attenuationExponent);
        float shaped = factor <= 0.0f
                ? 0.0f
                : (factor >= 1.0f ? 1.0f : (float) Math.pow(factor, exp));
        return baseVolume * shaped;
    }

    double voiceScore(Vec3 listenerPos, float attenuationExponent) {
        // Sound priority dominates; predicted audible contribution breaks ties.
        return (priority * 1000.0) + Math.min(999.0, estimateAudibleGain(listenerPos, attenuationExponent) * 999.0);
    }

    /**
     * Attach an OpenAL source and resume at the authoritative logical cursor.
     * The source is fully initialized (including gain) before alSourcePlay, which
     * closes the old transient-gain window responsible for distant sound flashes.
     */
    boolean materializePhysicalVoice(Vec3 listenerPos, float attenuationExponent) {
        if (disposed.get() || resourcesFreed.get()) return false;
        if (!bindingRequested.get()) return false;
        if (!startedPlayback.get() || paused.get()) return false;
        if (!isStreamed && alBuffer == -1) return false;

        synchronized (sourceLifecycleLock) {
            if (source != null) return true;
            // Re-check after taking the lifecycle lock. play/stop/dispose may race
            // with the scheduler (async creation can complete off-thread), and a
            // stopped voice must never materialize from a stale pre-lock decision.
            if (disposed.get() || resourcesFreed.get() || !bindingRequested.get()
                    || !startedPlayback.get() || paused.get()) {
                return false;
            }

            OpenALSourcePool.SourceHandle handle = sourcePool.acquire(this);
            if (handle == null) return false;

            try {
                if (isStreamed) {
                    ensureStreamingBuffers();
                }

                source = handle;
                final int sourceId = handle.sourceId();
                final double cursor = normalizedCursorForPlayback();
                final float initialGain = estimateAudibleGain(listenerPos, attenuationExponent);
                smoothedVolume = volume;

                al.executeBlocking(() -> {
                    if (source != handle) return;
                    resetSourceOnALThread(sourceId);
                    applyNonGainParams(sourceId);
                    AL10.alSourcef(sourceId, AL10.AL_GAIN, initialGain);

                    if (isStreamed) {
                        AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
                        if (streamDecoder != null) {
                            streamDecoder.seekSeconds(cursor);
                            queueInitialBuffers(sourceId);
                        }
                    } else {
                        AL10.alSourcei(sourceId, AL10.AL_BUFFER, alBuffer);
                        AL10.alSourcei(sourceId, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
                        if (cursor > 0.0) {
                            AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, (float) cursor);
                        }
                    }

                    AL10.alSourcePlay(sourceId);
                });

                fireEvent(AuralisSoundEvent.BIND);
                return true;
            } catch (Throwable t) {
                GFBsAuralis.LOGGER.warn("Failed to materialize Auralis physical voice: {}", t.getMessage());
                source = null;
                try {
                    al.executeBlocking(() -> resetSourceOnALThread(handle.sourceId()));
                } catch (Throwable ignored) {
                }
                sourcePool.release(handle);
                return false;
            }
        }
    }

    /** Release only the physical renderer; logical playback keeps advancing. */
    void virtualizePhysicalVoice() {
        // BIND/UNBIND describe attachment of the scarce physical renderer. The
        // logical voice remains alive and its playback clock keeps advancing.
        releasePhysicalVoice(true);
    }

    private void releasePhysicalVoice(boolean fireUnbindEvent) {
        synchronized (sourceLifecycleLock) {
            OpenALSourcePool.SourceHandle handle = source;
            if (handle == null) return;

            source = null;
            final int sourceId = handle.sourceId();
            try {
                al.executeBlocking(() -> resetSourceOnALThread(sourceId));
            } catch (Throwable ignored) {
            } finally {
                sourcePool.release(handle);
            }

            // Streaming buffers are physical rendering resources as well. Do not
            // let every streamed logical voice that was audible once permanently
            // retain four OpenAL buffers after it becomes virtual. They are safely
            // recreated on the next materialization; the decoder/cursor stays alive.
            if (isStreamed) {
                int[] buffers = streamingBuffers;
                streamingBuffers = null;
                if (buffers != null) {
                    bufferCache.deleteBuffers(buffers);
                }
            }

            if (fireUnbindEvent) {
                fireEvent(AuralisSoundEvent.UNBIND);
            }
        }
    }

    private void restartPhysicalPlaybackFromLogicalCursor(OpenALSourcePool.SourceHandle handle) {
        final double cursor = normalizedCursorForPlayback();
        final int sourceId = handle.sourceId();
        al.executeBlocking(() -> {
            if (source != handle) return;

            if (isStreamed) {
                resetSourceOnALThread(sourceId);
                applyNonGainParams(sourceId);
                // Keep the current safe gain; the next attenuation pass updates it.
                if (streamDecoder != null) {
                    streamDecoder.seekSeconds(cursor);
                    queueInitialBuffers(sourceId);
                }
            } else {
                AL10.alSourceStop(sourceId);
                AL10.alSourceRewind(sourceId);
                if (cursor > 0.0) {
                    AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, (float) cursor);
                }
            }
            AL10.alSourcePlay(sourceId);
        });
    }

    private double normalizedCursorForPlayback() {
        synchronized (playbackClockLock) {
            double cursor = Math.max(0.0, logicalPlaybackSeconds);
            if (durationSeconds > 0.0) {
                if (looping) {
                    cursor %= durationSeconds;
                } else {
                    double lastSampleGuard = isStreamed && streamDecoder != null
                            ? (1.0 / Math.max(1, streamDecoder.getSampleRate()))
                            : 0.000_001;
                    cursor = Math.min(cursor, Math.max(0.0, durationSeconds - lastSampleGuard));
                }
            }
            return Math.max(0.0, cursor);
        }
    }

    private void ensureStreamingBuffers() {
        if (!isStreamed || streamingBuffers != null) return;
        streamingBuffers = bufferCache.createStreamingBuffers(STREAM_BUFFER_COUNT);
    }

    void updatePhysicalOnALThread(Vec3 listenerPos, float attenuationExponent, float volumeSmoothing) {
        if (!al.isOnALThread()) return;
        OpenALSourcePool.SourceHandle handle = source;
        if (handle == null) return;

        int sourceId = handle.sourceId();
        applyVelocityZeroOnALThread(sourceId);
        applyDistanceAttenuationOnALThread(sourceId, listenerPos, attenuationExponent, volumeSmoothing);
        if (isStreamed) {
            updateStreamedBuffersOnALThread(sourceId);
        }

        // Fallback for unusual files/drivers where duration could not be determined.
        if (!looping && durationSeconds <= 0.0 && startedPlayback.get() && !paused.get()) {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                pendingCompletionWasPhysical.set(true);
                startedPlayback.set(false);
                pendingNaturalCompletion.set(true);
            }
        }
    }

    private void pushNonGainParamsIfBound() {
        OpenALSourcePool.SourceHandle handle = source;
        if (handle == null) return;
        final int sourceId = handle.sourceId();
        submitALTask(() -> {
            if (source == handle) {
                applyNonGainParams(sourceId);
            }
        });
    }

    private void applyNonGainParams(int sourceId) {
        float effectivePitch = clamp(pitch * speed, 0.01f, 8.0f);
        AL10.alSourcef(sourceId, AL10.AL_PITCH, effectivePitch);

        if (isStatic) {
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, 0f, 0f, 0f);
        } else {
            Vec3 p = position;
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) p.x, (float) p.y, (float) p.z);
        }

        // Auralis computes attenuation itself. OpenAL is used for spatial panning only.
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0f);
        AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 1.0f);
        AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 1_000_000.0f);
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);
    }

    private void applyVelocityZeroOnALThread(int sourceId) {
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);
    }

    private void applyDistanceAttenuationOnALThread(
            int sourceId,
            Vec3 listenerPos,
            float attenuationExponent,
            float volumeSmoothing
    ) {
        float smoothing = clamp(volumeSmoothing, 0.0f, 1.0f);
        float smoothed = smoothedVolume + (volume - smoothedVolume) * smoothing;
        smoothedVolume = smoothed;

        if (isStatic) {
            AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, smoothed));
            return;
        }

        Vec3 src = position;
        double dx = src.x - listenerPos.x;
        double dy = src.y - listenerPos.y;
        double dz = src.z - listenerPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float factor = distanceFactor(distance);

        float exp = Math.max(0.0001f, attenuationExponent);
        float shaped = factor <= 0.0f
                ? 0.0f
                : (factor >= 1.0f ? 1.0f : (float) Math.pow(factor, exp));
        AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, smoothed * shaped));
    }

    private float distanceFactor(double distance) {
        float minD = Math.max(0.0f, minDistance);
        float maxD = Math.max(0.0f, maxDistance);

        if (maxD <= minD) {
            return distance <= minD ? 1.0f : 0.0f;
        }
        if (distance <= minD) return 1.0f;
        if (distance >= maxD) return 0.0f;
        return 1.0f - (float) ((distance - minD) / (maxD - minD));
    }

    /**
     * Return a pooled OpenAL source to a clean, type-neutral state.
     *
     * <p>OpenAL reports a static source's single attached buffer through
     * {@code AL_BUFFERS_QUEUED} as well. Calling {@code alSourceUnqueueBuffers}
     * on that source is invalid and leaves AL_INVALID_VALUE behind for the
     * strict Auralis AL-thread check. Only streaming sources may be unqueued.</p>
     *
     * <p>For streaming sources, only buffers OpenAL reports as processed are
     * unqueued. This avoids a second AL_INVALID_VALUE path on implementations
     * that do not expose every queued buffer as processed immediately after a
     * stop. {@code AL_BUFFER = 0} then detaches any remaining queue/binding and
     * returns the stopped source to an undetermined type.</p>
     */
    private void resetSourceOnALThread(int sourceId) {
        try {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
                AL10.alSourceStop(sourceId);
            }

            int sourceType = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_TYPE);
            if (sourceType == AL11.AL_STREAMING) {
                // Streaming is implemented by Auralis itself; never leave the
                // OpenAL looping flag set while draining a recycled source.
                AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);

                int queued = Math.max(0, AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED));
                int processed = Math.max(0, AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED));
                int removable = Math.min(queued, processed);

                if (removable > 0) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer tmp = stack.mallocInt(removable);
                        AL10.alSourceUnqueueBuffers(sourceId, tmp);
                    }
                }
            }

            // Detach a static buffer, or clear any remaining streaming queue,
            // and put the pooled source back into AL_UNDETERMINED state.
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            AL10.alSourceRewind(sourceId);

            // Safe baseline: a recycled source is silent until the new owner has
            // applied its complete spatial state. This is the key anti-flash guard.
            AL10.alSourcef(sourceId, AL10.AL_GAIN, 0.0f);
        } catch (Throwable ignored) {
        }
    }

    private void updateStreamedBuffersOnALThread(int sourceId) {
        if (!isStreamed || streamDecoder == null) return;

        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        if (processed > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer tmp = stack.mallocInt(processed);
                AL10.alSourceUnqueueBuffers(sourceId, tmp);
                for (int i = 0; i < processed; i++) {
                    refillAndQueue(sourceId, tmp.get(i));
                }
            } catch (Throwable ignored) {
            }
        }

        if (!paused.get() && startedPlayback.get()) {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) {
                    AL10.alSourcePlay(sourceId);
                }
            }
        }
    }

    private void queueInitialBuffers(int sourceId) {
        int[] buffers = streamingBuffers;
        if (buffers == null) return;
        for (int bufferId : buffers) {
            if (!refillAndQueue(sourceId, bufferId)) break;
        }
    }

    private boolean refillAndQueue(int sourceId, int bufferId) {
        if (streamDecoder == null || decodeBuffer == null) return false;

        decodeBuffer.clear();
        int bytes = streamDecoder.decodeChunk(decodeBuffer);
        if (bytes <= 0 && looping) {
            streamDecoder.seekStart();
            decodeBuffer.clear();
            bytes = streamDecoder.decodeChunk(decodeBuffer);
        }

        if (bytes <= 0) return false;
        decodeBuffer.flip();

        if (!processorChain.isEmpty()) {
            int channels = streamDecoder.getChannels();
            int rate = streamDecoder.getSampleRate();
            int currentBytes = decodeBuffer.limit();

            for (AudioProcessor processor : processorChain) {
                if (!processor.isEnabled()) continue;
                int pos = decodeBuffer.position();
                int newBytes = processor.process(decodeBuffer, channels, rate, currentBytes);
                decodeBuffer.position(pos);
                int maximumBytes = decodeBuffer.capacity() - pos;
                int frameBytes = Math.max(1, channels * 2);
                if (newBytes < 0 || newBytes > maximumBytes || (newBytes % frameBytes) != 0) {
                    throw new IllegalStateException(
                            "Audio processor returned invalid byte count " + newBytes
                                    + " (available=" + maximumBytes + ", frameBytes=" + frameBytes + ")"
                    );
                }
                if (newBytes != currentBytes) {
                    decodeBuffer.limit(pos + newBytes);
                    currentBytes = newBytes;
                }
            }
            if (currentBytes == 0) return false;
        }

        AL10.alBufferData(bufferId, streamDecoder.getAlFormat(), decodeBuffer, streamDecoder.getSampleRate());
        AL10.alSourceQueueBuffers(sourceId, bufferId);
        return true;
    }

    boolean finalizeNaturalCompletionIfNeeded() {
        if (!pendingNaturalCompletion.compareAndSet(true, false)) return false;

        boolean hadPhysicalSource = pendingCompletionWasPhysical.getAndSet(false) || source != null;
        releasePhysicalVoice(false);
        fireEvent(AuralisSoundEvent.STOP);
        if (hadPhysicalSource) {
            fireEvent(AuralisSoundEvent.UNBIND);
        }
        if (!autoDisposeOnFinish) {
            return false;
        }

        disposed.set(true);
        bindingRequested.set(false);
        freeBuffers();
        pendingEngineRemoval.set(true);
        return true;
    }

    boolean consumePendingEngineRemoval() {
        return pendingEngineRemoval.compareAndSet(true, false);
    }

    void disposeExplicitly() {
        if (!disposed.compareAndSet(false, true)) return;

        pendingNaturalCompletion.set(false);
        pendingCompletionWasPhysical.set(false);
        bindingRequested.set(false);
        startedPlayback.set(false);
        paused.set(false);
        releasePhysicalVoice(true);
        freeBuffers();
        pendingEngineRemoval.set(false);
    }

    void markDisposedAfterSourcePoolShutdown() {
        disposed.set(true);
        pendingNaturalCompletion.set(false);
        pendingCompletionWasPhysical.set(false);
        bindingRequested.set(false);
        startedPlayback.set(false);
        paused.set(false);
        source = null;
    }

    void forceStopAndFree() {
        if (!disposed.compareAndSet(false, true)) return;
        pendingNaturalCompletion.set(false);
        pendingCompletionWasPhysical.set(false);
        bindingRequested.set(false);
        startedPlayback.set(false);
        paused.set(false);
        releasePhysicalVoice(true);
        fireEvent(AuralisSoundEvent.FORCE_STOP);
        freeBuffers();
        pendingEngineRemoval.set(true);
    }

    void onEvicted() {
        // 2.1 eviction means physical -> virtual, never instance destruction.
        fireEvent(AuralisSoundEvent.EVICTED);
        virtualizePhysicalVoice();
    }

    void freeBuffers() {
        if (!resourcesFreed.compareAndSet(false, true)) return;

        if (isStreamed) {
            final int[] buffers;
            synchronized (sourceLifecycleLock) {
                buffers = streamingBuffers;
                streamingBuffers = null;
            }

            try {
                // This is a barrier behind every previously accepted stream update.
                // Decoder state, its decode workspace, and OpenAL stream buffers are
                // all released by their sole owner thread, never by a render/network
                // thread racing an in-flight native call.
                al.executeBlocking(() -> freeStreamResourcesOnALThread(buffers));
            } catch (Throwable t) {
                // Leaking a little native memory during a broken/late shutdown is
                // safer than freeing it concurrently and crashing the entire JVM.
                GFBsAuralis.LOGGER.warn(
                        "Unable to serialize streamed resource cleanup on the OpenAL thread; resources will be reclaimed at process exit",
                        t
                );
            }
        } else if (alBuffer != -1) {
            bufferCache.releaseBuffer(alBuffer);
        }
    }

    private void freeStreamResourcesOnALThread(@Nullable int[] buffers) {
        Throwable failure = null;
        try {
            if (streamDecoder != null) streamDecoder.close();
        } catch (Throwable t) {
            failure = t;
        }
        try {
            if (decodeBuffer != null) MemoryUtil.memFree(decodeBuffer);
        } catch (Throwable t) {
            if (failure == null) failure = t; else failure.addSuppressed(t);
        }
        if (buffers != null) {
            for (int bufferId : buffers) {
                if (bufferId == 0) continue;
                try {
                    AL10.alDeleteBuffers(bufferId);
                } catch (Throwable t) {
                    if (failure == null) failure = t; else failure.addSuppressed(t);
                }
            }
        }

        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException("Failed to release streamed audio resources", failure);
    }

    private void submitALTask(Runnable task) {
        try {
            al.submit(task);
        } catch (RuntimeException rejectedDuringShutdown) {
            GFBsAuralis.LOGGER.debug("Ignoring late Auralis OpenAL update during shutdown: {}", rejectedDuringShutdown.getMessage());
        }
    }

    private void fireEvent(AuralisSoundEvent event) {
        for (AuralisSoundListener listener : listeners) {
            try {
                listener.onSoundEvent(this, event);
            } catch (Exception e) {
                GFBsAuralis.LOGGER.error("Error in sound listener: {}", e.getMessage(), e);
            }
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
