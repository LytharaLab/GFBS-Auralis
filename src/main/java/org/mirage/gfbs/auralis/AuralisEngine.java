package org.mirage.gfbs.auralis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.mirage.gfbs.auralis.api.AuralisSoundInstance;
import org.mirage.gfbs.auralis.api.IAuralisEngine;
import org.mirage.gfbs.auralis.api.event.SoundCreatedEvent;
import org.mirage.gfbs.auralis.api.processing.AudioProcessor;
import org.mirage.gfbs.auralis.core.AuralisPluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuralisEngine implements IAuralisEngine {
    private final Minecraft mc;
    private final AuralisAL al;

    private final OpenALSourcePool sourcePool;
    private final SoundBufferCache bufferCache;
    private final AuralisPluginManager pluginManager;
    private final float attenuationExponent;
    private final float volumeSmoothing;
    private final int streamedChunkSize;

    private final ConcurrentMap<AuralisSoundInstance, AuralisSoundInstanceImpl> instances = new ConcurrentHashMap<>();

    public AuralisEngine(
            Minecraft mc,
            AuralisAL al,
            int maxSources,
            int streamedChunkSize,
            int maxStreamedBytes,
            float attenuationExponent,
            float volumeSmoothing
    ) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.al = Objects.requireNonNull(al, "al");

        this.sourcePool = new OpenALSourcePool(al, maxSources);
        this.bufferCache = new SoundBufferCache(mc, al, streamedChunkSize, maxStreamedBytes);
        this.pluginManager = new AuralisPluginManager();
        this.streamedChunkSize = streamedChunkSize;
        this.attenuationExponent = attenuationExponent;
        this.volumeSmoothing = volumeSmoothing;
    }

    public AuralisPluginManager getPluginManager() {
        return pluginManager;
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
                int[] buffers = bufferCache.createStreamingBuffers(4);
                inst = new AuralisSoundInstanceImpl(al, decoder, buffers, streamedChunkSize, bufferCache, sourcePool);
            } else {
                int bufferId = bufferCache.acquireBuffer(soundPath);
                if (bufferId == -1) {
                    throw new RuntimeException("Failed to acquire valid buffer for sound: " + soundPath);
                }
                inst = new AuralisSoundInstanceImpl(al, bufferId, bufferCache, sourcePool);
            }

            // Apply global processors
            for (AudioProcessor p : pluginManager.getGlobalProcessors()) {
                inst.addProcessor(p);
            }

            // Fire event
            pluginManager.getEventBus().post(new SoundCreatedEvent(inst, soundEvent));

            instances.put(inst, inst);
            return inst;
        } catch (Exception e) {
            GFBsAuralis.LOGGER.error("Failed to create sound instance for: {} ;E: {}", eventId, e.getMessage());
            return new AuralisSoundInstanceImpl(al, -1, bufferCache, sourcePool);
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
                        .thenApply(decoder -> {
                            int[] buffers = bufferCache.createStreamingBuffers(4);
                            return new AuralisSoundInstanceImpl(al, decoder, buffers, streamedChunkSize, bufferCache, sourcePool);
                        });
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
                // Apply global processors
                for (AudioProcessor p : pluginManager.getGlobalProcessors()) {
                    inst.addProcessor(p);
                }

                // Fire event
                pluginManager.getEventBus().post(new SoundCreatedEvent(inst, soundEvent));

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

    private ResourceLocation resolveSoundPath(ResourceLocation eventId) {
        Sound chosen = resolveToConcreteSound(eventId);
        ResourceLocation raw = chosen.getLocation();
        String ns = raw.getNamespace();
        String path = raw.getPath();
        
        // Normalize path to assets/<namespace>/sounds/<path>.ogg
        // ResourceLocation in Minecraft assumes "sounds/" prefix is NOT part of the path if loaded via SoundManager?
        // Actually, SoundManager loads sounds.json.
        // If sounds.json says "category": "record", "name": "music/disc/cat"
        // It looks for assets/minecraft/sounds/music/disc/cat.ogg
        // The ResourceLocation for getResource is "minecraft:sounds/music/disc/cat.ogg".
        
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
        Sound s = events.getSound(rnd);
        if (s == SoundManager.EMPTY_SOUND) {
            throw new IllegalStateException("SoundEvent resolved to EMPTY_SOUND: " + soundEventId);
        }
        return s;
    }

    @Override
    public void bind(AuralisSoundInstance instance) {
        AuralisSoundInstanceImpl impl = requireImpl(instance);
        impl.bind();
    }

    @Override
    public void unbind(AuralisSoundInstance instance) {
        AuralisSoundInstanceImpl impl = requireImpl(instance);
        try {
            impl.unbind();
        } finally {
            try {
                impl.freeBuffers();
            } finally {
                instances.remove(impl);
            }
        }
    }

    @Override
    public void tick() {
        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                inst.processPendingBindAndPlay();
            } catch (Throwable ignored) {
            }
        }

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 listenerPos = cam.getPosition();
        float pitch = cam.getXRot();
        float yaw = cam.getYRot();
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 up = Vec3.directionFromRotation(pitch - 90.0F, yaw);

        al.submit(() -> {
            AL10.alDopplerFactor(0.0f);

            AL10.alListener3f(AL10.AL_POSITION, (float) listenerPos.x, (float) listenerPos.y, (float) listenerPos.z);
            AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);

            float[] ori = new float[]{
                    (float) forward.x, (float) forward.y, (float) forward.z,
                    (float) up.x, (float) up.y, (float) up.z
            };
            AL10.alListenerfv(AL10.AL_ORIENTATION, ori);

            for (AuralisSoundInstanceImpl inst : instances.values()) {
                inst.updateStreamedBuffersOnALThread();
                inst.disposeIfNaturallyStoppedOnALThread();
                inst.applyVelocityZeroOnALThread();
                inst.applyDistanceAttenuationOnALThread(listenerPos, attenuationExponent, volumeSmoothing);
            }
        });

        sourcePool.tickRecycleEndedSources();

        List<AuralisSoundInstanceImpl> toRemove = new ArrayList<>();
        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                if (inst.finalizeNaturalDisposeIfNeeded() || inst.consumePendingEngineRemoval()) {
                    toRemove.add(inst);
                }
            } catch (Throwable ignored) {
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
        // 1. Stop all instances first to ensure playback stops.
        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                inst.stop();
            } catch (Throwable ignored) {
            }
        }

        // 2. Close source pool (deletes all OpenAL sources).
        // This is crucial: Deleting sources automatically detaches all buffers.
        // If we delete buffers while they are still attached (even if stopped),
        // OpenAL throws AL_INVALID_OPERATION.
        sourcePool.close();

        // 3. Free buffers (now safe to delete as they are detached).
        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
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
        if (instance instanceof AuralisSoundInstanceImpl impl) return impl;
        AuralisSoundInstanceImpl mapped = instances.get(instance);
        if (mapped != null) return mapped;
        throw new IllegalArgumentException("Not an Auralis engine instance: " + instance);
    }
}
