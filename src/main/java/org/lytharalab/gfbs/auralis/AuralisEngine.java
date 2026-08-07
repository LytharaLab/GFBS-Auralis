package org.lytharalab.gfbs.auralis;
/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 *
 * This program is licensed under the MIT License.
 */

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import org.lytharalab.gfbs.auralis.api.IAuralisEngine;
import org.lytharalab.gfbs.auralis.api.event.SoundCreatedEvent;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;
import org.lytharalab.gfbs.auralis.core.AuralisPluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuralisEngine implements IAuralisEngine {
    private static final float DEFAULT_VOICE_MATERIALIZE_GAIN = 0.0010f;
    private static final float DEFAULT_VOICE_VIRTUALIZE_GAIN = 0.00025f;
    private final Minecraft mc;
    private final AuralisAL al;

    private final OpenALSourcePool sourcePool;
    private final SoundBufferCache bufferCache;
    private final AuralisPluginManager pluginManager;
    private final AuralisVoiceManager voiceManager;
    private final float attenuationExponent;
    private final float volumeSmoothing;
    private final int streamedChunkSize;

    private final ConcurrentMap<AuralisSoundInstance, AuralisSoundInstanceImpl> instances = new ConcurrentHashMap<>();

    /**
     * Backwards-compatible constructor retained for integrations compiled against
     * the pre-2.1 engine constructor. Voice virtualization uses safe defaults.
     */
    public AuralisEngine(
            Minecraft mc,
            AuralisAL al,
            int maxSources,
            int streamedChunkSize,
            int maxStreamedBytes,
            float attenuationExponent,
            float volumeSmoothing
    ) {
        this(
                mc,
                al,
                maxSources,
                streamedChunkSize,
                maxStreamedBytes,
                attenuationExponent,
                volumeSmoothing,
                DEFAULT_VOICE_MATERIALIZE_GAIN,
                DEFAULT_VOICE_VIRTUALIZE_GAIN
        );
    }

    public AuralisEngine(
            Minecraft mc,
            AuralisAL al,
            int maxSources,
            int streamedChunkSize,
            int maxStreamedBytes,
            float attenuationExponent,
            float volumeSmoothing,
            float voiceMaterializeGain,
            float voiceVirtualizeGain
    ) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.al = Objects.requireNonNull(al, "al");

        this.sourcePool = new OpenALSourcePool(al, maxSources);
        this.bufferCache = new SoundBufferCache(mc, al, streamedChunkSize, maxStreamedBytes);
        this.pluginManager = new AuralisPluginManager();
        this.voiceManager = new AuralisVoiceManager(sourcePool, voiceMaterializeGain, voiceVirtualizeGain);
        this.streamedChunkSize = streamedChunkSize;
        this.attenuationExponent = attenuationExponent;
        this.volumeSmoothing = volumeSmoothing;
    }

    public AuralisPluginManager getPluginManager() {
        return pluginManager;
    }

    /** Number of retained logical instances, including stopped reusable instances. */
    @Override
    public int getLogicalVoiceCount() {
        return voiceManager.getLogicalVoiceCount();
    }

    /** Number of logical voices whose playback clocks are currently running. */
    @Override
    public int getPlayingVoiceCount() {
        return voiceManager.getPlayingVoiceCount();
    }

    /** Number of logical voices currently backed by real OpenAL sources. */
    @Override
    public int getPhysicalVoiceCount() {
        return voiceManager.getPhysicalVoiceCount();
    }

    /** Number of currently-playing logical voices without an OpenAL source. */
    @Override
    public int getVirtualVoiceCount() {
        return voiceManager.getVirtualVoiceCount();
    }

    @Override
    public AuralisSoundInstance create(SoundEvent soundEvent) {
        return create(soundEvent, false);
    }

    public AuralisSoundInstance createStreamed(SoundEvent soundEvent) {
        return create(soundEvent, true);
    }

    public CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent) {
        return createAsync(soundEvent, false);
    }

    public CompletableFuture<AuralisSoundInstance> createStreamedAsync(SoundEvent soundEvent) {
        return createAsync(soundEvent, true);
    }

    private AuralisSoundInstance create(SoundEvent soundEvent, boolean streamed) {
        Objects.requireNonNull(soundEvent, "soundEvent");
        ResourceLocation eventId = soundEvent.getLocation();

        try {
            ResourceLocation soundPath = resolveSoundPath(eventId);

            AuralisSoundInstanceImpl inst;
            if (streamed) {
                var decoder = bufferCache.createStreamDecoder(soundPath);
                inst = new AuralisSoundInstanceImpl(al, decoder, streamedChunkSize, bufferCache, sourcePool);
            } else {
                int bufferId = bufferCache.acquireBuffer(soundPath);
                if (bufferId == -1) {
                    throw new RuntimeException("Failed to acquire valid buffer for sound: " + soundPath);
                }
                inst = new AuralisSoundInstanceImpl(al, bufferId, bufferCache, sourcePool);
            }

            configureNewInstance(inst, soundEvent);
            instances.put(inst, inst);
            return inst;
        } catch (Exception e) {
            GFBsAuralis.LOGGER.error("Failed to create sound instance for: {} ;E: {}", eventId, e.getMessage());
            AuralisSoundInstanceImpl fallback = new AuralisSoundInstanceImpl(al, -1, bufferCache, sourcePool);
            instances.put(fallback, fallback);
            return fallback;
        }
    }

    private CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent, boolean streamed) {
        Objects.requireNonNull(soundEvent, "soundEvent");
        ResourceLocation eventId = soundEvent.getLocation();

        try {
            ResourceLocation soundPath = resolveSoundPath(eventId);

            CompletableFuture<AuralisSoundInstanceImpl> futureInst;
            if (streamed) {
                futureInst = bufferCache.createStreamDecoderAsync(soundPath)
                        .thenApply(decoder -> new AuralisSoundInstanceImpl(
                                al,
                                decoder,
                                streamedChunkSize,
                                bufferCache,
                                sourcePool
                        ));
            } else {
                futureInst = bufferCache.acquireBufferAsync(soundPath)
                        .thenApply(bufferId -> {
                            if (bufferId == -1) {
                                throw new RuntimeException("Failed to acquire valid buffer for sound: " + soundPath);
                            }
                            return new AuralisSoundInstanceImpl(al, bufferId, bufferCache, sourcePool);
                        });
            }

            return futureInst.thenApply(inst -> {
                configureNewInstance(inst, soundEvent);
                instances.put(inst, inst);
                return (AuralisSoundInstance) inst;
            }).exceptionally(ex -> {
                GFBsAuralis.LOGGER.error("Failed to create sound instance asynchronously for: {} ;E: {}", eventId, ex.getMessage());
                AuralisSoundInstanceImpl fallback = new AuralisSoundInstanceImpl(al, -1, bufferCache, sourcePool);
                instances.put(fallback, fallback);
                return fallback;
            });
        } catch (Exception e) {
            GFBsAuralis.LOGGER.error("Failed to create sound instance for: {} ;E: {}", eventId, e.getMessage());
            AuralisSoundInstanceImpl fallback = new AuralisSoundInstanceImpl(al, -1, bufferCache, sourcePool);
            instances.put(fallback, fallback);
            return CompletableFuture.completedFuture(fallback);
        }
    }

    private void configureNewInstance(AuralisSoundInstanceImpl inst, SoundEvent soundEvent) {
        for (AudioProcessor processor : pluginManager.getGlobalProcessors()) {
            inst.addProcessor(processor);
        }
        pluginManager.getEventBus().post(new SoundCreatedEvent(inst, soundEvent));
    }

    private ResourceLocation resolveSoundPath(ResourceLocation eventId) {
        Sound chosen = resolveToConcreteSound(eventId);
        ResourceLocation raw = chosen.getLocation();
        String ns = raw.getNamespace();
        String path = raw.getPath();

        StringBuilder sb = new StringBuilder();
        if (!path.startsWith("sounds/")) {
            sb.append("sounds/");
        }
        sb.append(path);
        if (!path.endsWith(".ogg")) {
            sb.append(".ogg");
        }

        return new ResourceLocation(ns, sb.toString());
    }

    private Sound resolveToConcreteSound(ResourceLocation soundEventId) {
        SoundManager sm = mc.getSoundManager();
        @Nullable WeighedSoundEvents events = sm.getSoundEvent(soundEventId);
        if (events == null) {
            throw new IllegalArgumentException("Unknown SoundEvent: " + soundEventId);
        }

        RandomSource rnd = RandomSource.create();
        Sound sound = events.getSound(rnd);
        if (sound == SoundManager.EMPTY_SOUND) {
            throw new IllegalStateException("SoundEvent resolved to EMPTY_SOUND: " + soundEventId);
        }
        return sound;
    }

    @Override
    public void bind(AuralisSoundInstance instance) {
        requireImpl(instance).bind();
    }

    @Override
    public void unbind(AuralisSoundInstance instance) {
        AuralisSoundInstanceImpl impl = requireImpl(instance);
        instances.remove(impl);
        impl.disposeExplicitly();
    }

    @Override
    public void tick() {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 listenerPos = cam.getPosition();
        float pitch = cam.getXRot();
        float yaw = cam.getYRot();
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 up = Vec3.directionFromRotation(pitch - 90.0F, yaw);
        long nowNanos = System.nanoTime();

        // Use a stable snapshot for this frame. Async creation may mutate the map.
        List<AuralisSoundInstanceImpl> snapshot = new ArrayList<>(instances.values());

        // Queue the listener update before any materialization. materializePhysicalVoice()
        // uses executeBlocking, so the OpenAL queue ordering guarantees a newly started
        // source sees the newest listener transform before it becomes audible.
        al.submit(() -> {
            AL10.alDopplerFactor(0.0f);
            AL10.alListener3f(AL10.AL_POSITION, (float) listenerPos.x, (float) listenerPos.y, (float) listenerPos.z);
            AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
            float[] ori = new float[]{
                    (float) forward.x, (float) forward.y, (float) forward.z,
                    (float) up.x, (float) up.y, (float) up.z
            };
            AL10.alListenerfv(AL10.AL_ORIENTATION, ori);
        });

        // Advances ALL logical clocks and maps only the currently useful subset to
        // physical OpenAL sources.
        voiceManager.tick(snapshot, listenerPos, attenuationExponent, nowNanos);

        // One batched AL task updates only physical voices. Virtual voices incur no
        // OpenAL, streaming decode, or distance-update work.
        al.submit(() -> {
            for (AuralisSoundInstanceImpl inst : snapshot) {
                if (!inst.isDisposed() && inst.isPhysicalVoice()) {
                    inst.updatePhysicalOnALThread(listenerPos, attenuationExponent, volumeSmoothing);
                }
            }
        });

        // This blocking defensive sweep also establishes a barrier after the AL update
        // above, so fallback natural-completion flags are visible below.
        sourcePool.tickRecycleEndedSources();

        List<AuralisSoundInstanceImpl> toRemove = new ArrayList<>();
        for (AuralisSoundInstanceImpl inst : snapshot) {
            try {
                if (inst.finalizeNaturalCompletionIfNeeded() || inst.consumePendingEngineRemoval()) {
                    toRemove.add(inst);
                }
            } catch (Throwable t) {
                GFBsAuralis.LOGGER.debug("Error finalizing Auralis voice: {}", t.getMessage());
            }
        }
        for (AuralisSoundInstanceImpl inst : toRemove) {
            instances.remove(inst);
        }
    }

    @Override
    public void shutdown() {
        shutdown(true);
    }

    public void shutdown(boolean stopOpenAL) {
        // Stop logical voices and release their physical sources first.
        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                inst.stop();
            } catch (Throwable ignored) {
            }
        }

        // Delete the source pool before deleting any buffers still known to instances.
        sourcePool.close();

        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                inst.markDisposedAfterSourcePoolShutdown();
                inst.freeBuffers();
            } catch (Throwable ignored) {
            }
        }
        instances.clear();

        pluginManager.shutdown();
        bufferCache.clearAll();
        bufferCache.shutdown();
        if (stopOpenAL) {
            AuralisAL.stopAndClearGlobal();
        }
    }

    private AuralisSoundInstanceImpl requireImpl(AuralisSoundInstance instance) {
        if (instance instanceof AuralisSoundInstanceImpl impl) {
            if (impl.isDisposed()) {
                throw new IllegalStateException("Auralis sound instance has already been disposed: " + instance);
            }
            return impl;
        }
        AuralisSoundInstanceImpl mapped = instances.get(instance);
        if (mapped != null && !mapped.isDisposed()) return mapped;
        throw new IllegalArgumentException("Not an Auralis engine instance: " + instance);
    }
}
