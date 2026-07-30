package org.lytharalab.gfbs.auralis;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
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
import java.util.concurrent.atomic.AtomicInteger;

final class AuralisSoundInstanceImpl implements AuralisSoundInstance {
    private final AuralisAL al;

    private final int alBuffer; // For static sounds
    
    // For streamed sounds
    private final OggVorbisDecoder.StreamDecoder streamDecoder;
    private final int[] streamingBuffers;
    private final ByteBuffer decodeBuffer;

    private final SoundBufferCache bufferCache;
    private final OpenALSourcePool sourcePool;

    private volatile float volume = 1.0f;
    private volatile float smoothedVolume = 1.0f;
    private volatile float pitch = 1.0f;
    private volatile float speed = 1.0f;

    private volatile boolean isStatic = false;
    private volatile boolean looping = false;
    private final boolean isStreamed;

    private volatile Vec3 position = Vec3.ZERO;

    private volatile float minDistance = 1.0f;
    private volatile float maxDistance = 48.0f;

    private volatile @Nullable OpenALSourcePool.SourceHandle source;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile int priority = 50;
    private final Set<AuralisSoundListener> listeners = new CopyOnWriteArraySet<>();
    private final List<AudioProcessor> processorChain = new CopyOnWriteArrayList<>();
    
    private final AtomicBoolean pendingBind = new AtomicBoolean(false);
    private final AtomicBoolean pendingPlay = new AtomicBoolean(false);
    private final AtomicBoolean startedPlayback = new AtomicBoolean(false);
    private final AtomicBoolean pendingNaturalDispose = new AtomicBoolean(false);
    private final AtomicBoolean pendingEngineRemoval = new AtomicBoolean(false);

    // Static constructor
    AuralisSoundInstanceImpl(AuralisAL al, int alBuffer, SoundBufferCache bufferCache, OpenALSourcePool sourcePool) {
        this.al = Objects.requireNonNull(al, "al");
        this.alBuffer = alBuffer;
        this.streamDecoder = null;
        this.streamingBuffers = null;
        this.decodeBuffer = null;
        this.bufferCache = Objects.requireNonNull(bufferCache, "bufferCache");
        this.sourcePool = Objects.requireNonNull(sourcePool, "sourcePool");
        this.isStreamed = false;
    }

    // Streamed constructor
    AuralisSoundInstanceImpl(AuralisAL al, OggVorbisDecoder.StreamDecoder streamDecoder, int[] streamingBuffers, int chunkSize, SoundBufferCache bufferCache, OpenALSourcePool sourcePool) {
        this.al = Objects.requireNonNull(al, "al");
        this.alBuffer = -1;
        this.streamDecoder = Objects.requireNonNull(streamDecoder, "streamDecoder");
        this.streamingBuffers = Objects.requireNonNull(streamingBuffers, "streamingBuffers");
        this.decodeBuffer = MemoryUtil.memAlloc(chunkSize);
        this.bufferCache = Objects.requireNonNull(bufferCache, "bufferCache");
        this.sourcePool = Objects.requireNonNull(sourcePool, "sourcePool");
        this.isStreamed = true;
    }

    @Override
    public boolean isBound() {
        return source != null;
    }

    void bind() {
        if (source != null) return;
        if (!isStreamed && alBuffer == -1) return;
        if (isStreamed && (streamDecoder == null || streamingBuffers == null)) return;

        OpenALSourcePool.SourceHandle h = sourcePool.acquire(this);
        if (h == null) {
            pendingBind.set(true);
            return;
        }
        this.source = h;
        pendingBind.set(false);
        final int sourceId = h.sourceId();

        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                AL10.alGetError();

                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
                    AL10.alSourceStop(sourceId);
                }

                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer tmp = stack.mallocInt(queued);
                        AL10.alSourceUnqueueBuffers(sourceId, tmp);
                    } catch (Throwable ignored) {}
                }

                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                AL10.alSourceRewind(sourceId);

                if (isStreamed) {
                    AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
                } else {
                    AL10.alSourcei(sourceId, AL10.AL_BUFFER, alBuffer);
                    AL10.alSourcei(sourceId, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
                }

                applyAllParams(sourceId);
                AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);

                AL10.alSourceRewind(sourceId);
            }
        });

        fireEvent(AuralisSoundEvent.BIND);
    }

    void unbind() {
        OpenALSourcePool.SourceHandle h = this.source;
        if (h == null) return;
        final int sourceId = h.sourceId();

        ((OpenALSourcePool) sourcePool).sourceToInstance.remove(h);

        al.executeBlocking(() -> {
            try {
                AL10.alSourceStop(sourceId);
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer tmp = stack.mallocInt(queued);
                        AL10.alSourceUnqueueBuffers(sourceId, tmp);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception ignored) {}
        });

        this.source = null;
        paused.set(false);
        sourcePool.release(h);
        pendingBind.set(false);
        pendingPlay.set(false);
        startedPlayback.set(false);

        fireEvent(AuralisSoundEvent.UNBIND);
    }

    @Override
    public void play() {
        if (!isStreamed && alBuffer == -1) return;
        if (isStreamed && (streamDecoder == null || streamingBuffers == null)) return;

        OpenALSourcePool.SourceHandle h = source;
        if (h == null) {
            pendingBind.set(true);
            pendingPlay.set(true);
            startedPlayback.set(true);
            return;
        }
        paused.set(false);
        startedPlayback.set(true);
        final int sourceId = h.sourceId();

        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                applyAllParams(sourceId);
                AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);

                if (isStreamed) {
                    int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                    int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);

                    if (queued > 0 && state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
                        if (processed >= queued) {
                            try (MemoryStack stack = MemoryStack.stackPush()) {
                                IntBuffer tmp = stack.mallocInt(queued);
                                AL10.alSourceUnqueueBuffers(sourceId, tmp);
                            } catch (Throwable ignored) {}
                            queued = 0;
                        }
                    }

                    if (queued == 0) {
                        if (streamDecoder.isEof()) {
                            streamDecoder.seekStart();
                        }
                        queueInitialBuffers(sourceId);
                    }
                } else {
                    int attached = AL10.alGetSourcei(sourceId, AL10.AL_BUFFER);
                    if (attached != alBuffer) {
                        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                        if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
                            AL10.alSourceStop(sourceId);
                        }
                        AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                        AL10.alSourcei(sourceId, AL10.AL_BUFFER, alBuffer);
                    }
                }

                AL10.alSourcePlay(sourceId);
            }
        });

        fireEvent(AuralisSoundEvent.PLAY);
    }

    @Override
    public void pause() {
        if (!isStreamed && alBuffer == -1) return;
        if (isStreamed && (streamDecoder == null || streamingBuffers == null)) return;

        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return;
        paused.set(true);
        final int sourceId = h.sourceId();

        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                AL10.alSourcePause(sourceId);
            }
        });

        fireEvent(AuralisSoundEvent.PAUSE);
    }

    @Override
    public void stop() {
        if (!isStreamed && alBuffer == -1) return;
        if (isStreamed && (streamDecoder == null || streamingBuffers == null)) return;

        OpenALSourcePool.SourceHandle h = source;
        if (h == null) {
            pendingBind.set(false);
            pendingPlay.set(false);
            startedPlayback.set(false);
            return;
        }
        paused.set(false);
        startedPlayback.set(false);
        final int sourceId = h.sourceId();

        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                AL10.alSourceStop(sourceId);
                AL10.alSourceRewind(sourceId);
                
                if (isStreamed) {
                    int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                    if (queued > 0) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer tmp = stack.mallocInt(queued);
                            AL10.alSourceUnqueueBuffers(sourceId, tmp);
                        } catch (Throwable ignored) {}
                    }
                    streamDecoder.seekStart();
                }
            }
        });

        fireEvent(AuralisSoundEvent.STOP);
    }

    @Override
    public boolean isPlaying() {
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return false;
        return al.callBlocking(() -> AL10.alGetSourcei(h.sourceId(), AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING);
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public AuralisSoundInstance setVolume(float volume) {
        float v = Float.isFinite(volume) ? volume : 0.0f;
        this.volume = Math.max(0.0f, v);
        pushParamsIfBound();
        return this;
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @Override
    public AuralisSoundInstance setPitch(float pitch) {
        float p = Float.isFinite(pitch) ? pitch : 1.0f;
        this.pitch = clamp(p, 0.01f, 8.0f);
        pushParamsIfBound();
        return this;
    }

    @Override
    public float getPitch() {
        return pitch;
    }

    @Override
    public AuralisSoundInstance setSpeed(float speed) {
        float s = Float.isFinite(speed) ? speed : 1.0f;
        this.speed = clamp(s, 0.01f, 8.0f);
        pushParamsIfBound();
        return this;
    }

    @Override
    public float getSpeed() {
        return speed;
    }

    @Override
    public AuralisSoundInstance setStatic(boolean isStatic) {
        this.isStatic = isStatic;
        pushParamsIfBound();
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
        pushParamsIfBound();
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
        pushParamsIfBound();
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
        pushParamsIfBound();
        return this;
    }

    @Override
    public float getMaxDistance() {
        return maxDistance;
    }

    @Override
    public AuralisSoundInstance setLooping(boolean looping) {
        this.looping = looping;
        OpenALSourcePool.SourceHandle h = source;
        if (h != null && !isStreamed) {
            // For static sounds, update AL_LOOPING directly
            final int sourceId = h.sourceId();
            al.submit(() -> {
                if (source != null && source.sourceId() == sourceId) {
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
        // Sort by priority (smaller value = higher priority)
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

    private void fireEvent(AuralisSoundEvent event) {
        for (AuralisSoundListener listener : listeners) {
            try {
                listener.onSoundEvent(this, event);
            } catch (Exception e) {
                GFBsAuralis.LOGGER.error("Error in sound listener: {}", e.getMessage(), e);
            }
        }
    }

    void forceStopAndFree() {
        OpenALSourcePool.SourceHandle h = this.source;
        if (h != null) {
            this.source = null;

            ((OpenALSourcePool) sourcePool).sourceToInstance.remove(h);

            al.executeBlocking(() -> {
                try {
                    AL10.alSourceStop(h.sourceId());
                    AL10.alSourcei(h.sourceId(), AL10.AL_BUFFER, 0);
                    
                    int queued = AL10.alGetSourcei(h.sourceId(), AL10.AL_BUFFERS_QUEUED);
                    if (queued > 0) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer tmp = stack.mallocInt(queued);
                            AL10.alSourceUnqueueBuffers(h.sourceId(), tmp);
                        } catch (Throwable ignored) {}
                    }
                } catch (Exception ignored) {}
            });

            paused.set(false);
            sourcePool.release(h);
            pendingBind.set(false);
            pendingPlay.set(false);
            startedPlayback.set(false);
            fireEvent(AuralisSoundEvent.FORCE_STOP);
            fireEvent(AuralisSoundEvent.UNBIND);
        }
        freeBuffers();
        pendingEngineRemoval.set(true);
    }

    void onEvicted() {
        fireEvent(AuralisSoundEvent.EVICTED);
        forceStopAndFree();
    }

    private void pushParamsIfBound() {
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return;
        final int sourceId = h.sourceId();
        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                applyAllParams(sourceId);
                AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);
            }
        });
    }

    void applyVelocityZeroOnALThread() {
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return;
        final int sourceId = h.sourceId();
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);
    }

    void applyDistanceAttenuationOnALThread(Vec3 listenerPos, float attenuationExponent, float volumeSmoothing) {
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return;
        final int sourceId = h.sourceId();

        float s = clamp(volumeSmoothing, 0.0f, 1.0f);
        float sv = smoothedVolume + (volume - smoothedVolume) * s;
        smoothedVolume = sv;

        if (isStatic) {
            AL10.alSourcef(sourceId, AL10.AL_GAIN, sv);
            return;
        }

        Vec3 src = position;
        double dx = src.x - listenerPos.x;
        double dy = src.y - listenerPos.y;
        double dz = src.z - listenerPos.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float minD = Math.max(0.0f, minDistance);
        float maxD = Math.max(0.0f, maxDistance);

        float factor;
        if (maxD <= minD) {
            factor = (d <= minD) ? 1.0f : 0.0f;
        } else if (d <= minD) {
            factor = 1.0f;
        } else if (d >= maxD) {
            factor = 0.0f;
        } else {
            factor = 1.0f - (float) ((d - minD) / (maxD - minD));
        }

        float exp = Math.max(0.0001f, attenuationExponent);
        float shaped = (factor <= 0.0f) ? 0.0f : (factor >= 1.0f ? 1.0f : (float) Math.pow(factor, exp));
        AL10.alSourcef(sourceId, AL10.AL_GAIN, sv * shaped);
    }

    void updateStreamedBuffers() {
        if (!isStreamed || source == null) return;
        
        final int sourceId = source.sourceId();
        al.submit(() -> {
            if (source != null && source.sourceId() == sourceId) {
                updateStreamedBuffersOnALThread(sourceId);
            }
        });
    }

    void updateStreamedBuffersOnALThread() {
        if (!al.isOnALThread()) return;
        if (!isStreamed) return;
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return;
        updateStreamedBuffersOnALThread(h.sourceId());
    }

    private void updateStreamedBuffersOnALThread(int sourceId) {
        // Unqueue processed buffers
        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        if (processed > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer tmp = stack.mallocInt(processed);
                AL10.alSourceUnqueueBuffers(sourceId, tmp);
                
                // Refill and queue them back
                for (int i = 0; i < processed; i++) {
                    int bufferId = tmp.get(i);
                    if (refillAndQueue(sourceId, bufferId)) {
                        // Success
                    } else {
                        // EOF or error
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // If stopped but not supposed to be (underrun), restart
        if (!paused.get() && startedPlayback.get()) {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                // Check if we have queued buffers
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) {
                    AL10.alSourcePlay(sourceId);
                }
            }
        }
    }

    private void applyAllParams(int sourceId) {
        AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);

        float effectivePitch = clamp(pitch * speed, 0.01f, 8.0f);
        AL10.alSourcef(sourceId, AL10.AL_PITCH, effectivePitch);

        if (isStatic) {
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, 0f, 0f, 0f);

            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 1.0f);
            AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 1000000.0f);
        } else {
            Vec3 p = position;
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) p.x, (float) p.y, (float) p.z);

            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 1.0f);
            AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 1000000.0f);
        }

        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0f, 0f, 0f);
    }

    private void queueInitialBuffers(int sourceId) {
        if (streamingBuffers == null) return;
        
        // Ensure we don't queue more than available free buffers
        // But initially all are free.
        for (int bufferId : streamingBuffers) {
            if (!refillAndQueue(sourceId, bufferId)) {
                break;
            }
        }
    }
    
    private boolean refillAndQueue(int sourceId, int bufferId) {
        if (streamDecoder == null) return false;
        
        decodeBuffer.clear();
        int bytes = streamDecoder.decodeChunk(decodeBuffer);
        
        if (bytes <= 0) {
            if (looping) {
                streamDecoder.seekStart();
                decodeBuffer.clear();
                bytes = streamDecoder.decodeChunk(decodeBuffer);
            }
        }
        
        if (bytes > 0) {
            decodeBuffer.flip();
            
            // Apply processors
            if (!processorChain.isEmpty()) {
                int channels = streamDecoder.getChannels();
                int rate = streamDecoder.getSampleRate();
                int currentBytes = decodeBuffer.limit();

                for (AudioProcessor processor : processorChain) {
                    if (processor.isEnabled()) {
                        int pos = decodeBuffer.position();
                        int newBytes = processor.process(decodeBuffer, channels, rate, currentBytes);
                        
                        decodeBuffer.position(pos); // Reset position for next processor
                        if (newBytes != currentBytes) {
                            decodeBuffer.limit(pos + newBytes);
                            currentBytes = newBytes;
                        }
                    }
                }
            }

            AL10.alBufferData(bufferId, streamDecoder.getAlFormat(), decodeBuffer, streamDecoder.getSampleRate());
            AL10.alSourceQueueBuffers(sourceId, bufferId);
            return true;
        }
        
        return false;
    }

    boolean processPendingBindAndPlay() {
        if (!pendingBind.get()) return false;
        if (source != null) {
            pendingBind.set(false);
            if (pendingPlay.getAndSet(false)) {
                play();
            }
            return true;
        }
        bind();
        if (source != null) {
            pendingBind.set(false);
            if (pendingPlay.getAndSet(false)) {
                play();
            }
            return true;
        }
        return false;
    }

    boolean disposeIfNaturallyStoppedOnALThread() {
        if (!al.isOnALThread()) return false;
        if (!startedPlayback.get()) return false;
        if (paused.get()) return false;
        if (looping) return false; // Looping sounds don't naturally stop usually, unless decoder fail
        
        OpenALSourcePool.SourceHandle h = source;
        if (h == null) return false;
        int sourceId = h.sourceId();
        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
        
        // For streamed sounds, we must check if we are truly done (EOF)
        // If state is STOPPED but we still have data (underrun), we shouldn't dispose.
        if (isStreamed) {
             if (state == AL10.AL_STOPPED && streamDecoder != null && streamDecoder.isEof()) {
                 // Really done
             } else if (state == AL10.AL_STOPPED) {
                 // Underrun or just started?
                 // If we have queued buffers, it might be an underrun that will be fixed in updateStreamedBuffers
                 // If queued == 0 and EOF, then done.
                 int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                 if (queued == 0 && streamDecoder.isEof()) {
                     // Done
                 } else {
                     return false;
                 }
             } else {
                 return false;
             }
        } else {
            if (state != AL10.AL_STOPPED) return false;
        }

        source = null;
        sourcePool.sourceToInstance.remove(h);

        try {
            AL10.alSourceStop(sourceId);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            if (queued > 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer tmp = stack.mallocInt(queued);
                    AL10.alSourceUnqueueBuffers(sourceId, tmp);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        paused.set(false);
        pendingBind.set(false);
        pendingPlay.set(false);
        startedPlayback.set(false);
        pendingNaturalDispose.set(true);
        sourcePool.release(h);
        return true;
    }

    boolean finalizeNaturalDisposeIfNeeded() {
        if (!pendingNaturalDispose.compareAndSet(true, false)) return false;
        fireEvent(AuralisSoundEvent.STOP);
        fireEvent(AuralisSoundEvent.UNBIND);
        freeBuffers();
        pendingEngineRemoval.set(true);
        return true;
    }

    boolean consumePendingEngineRemoval() {
        return pendingEngineRemoval.compareAndSet(true, false);
    }

    void freeBuffers() {
        if (isStreamed) {
            // Close decoder and free decode buffer
            if (streamDecoder != null) {
                streamDecoder.close();
            }
            if (decodeBuffer != null) {
                MemoryUtil.memFree(decodeBuffer);
            }
            // Delete OpenAL buffers
            if (streamingBuffers != null) {
                bufferCache.deleteBuffers(streamingBuffers);
            }
        } else {
            bufferCache.releaseBuffer(alBuffer);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }


    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
