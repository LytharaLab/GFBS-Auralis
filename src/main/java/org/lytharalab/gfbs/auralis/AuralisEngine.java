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
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import org.lytharalab.gfbs.auralis.api.IAuralisEngine;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectRegistry;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffects;
import org.lytharalab.gfbs.auralis.api.event.SoundCreatedEvent;
import org.lytharalab.gfbs.auralis.api.openal.OpenALAccess;
import org.lytharalab.gfbs.auralis.api.plugin.AuralisPluginService;
import org.lytharalab.gfbs.auralis.core.AuralisPluginManager;
import org.lytharalab.gfbs.auralis.core.bus.AuralisBusManager;
import org.lytharalab.gfbs.auralis.core.bus.BusMixSnapshot;
import org.lytharalab.gfbs.auralis.core.effect.AuralisEffectRegistryImpl;
import org.lytharalab.gfbs.auralis.core.effect.OpenALEffectRack;
import org.lytharalab.gfbs.auralis.core.openal.OpenALAccessImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuralisEngine implements IAuralisEngine {
    private static final float DEFAULT_VOICE_MATERIALIZE_GAIN = 0.0010f;
    private static final float DEFAULT_VOICE_VIRTUALIZE_GAIN = 0.00025f;
    private final Minecraft mc;
    private final AuralisAL al;

    private final OpenALSourcePool sourcePool;
    private final SoundBufferCache bufferCache;
    private final AuralisPluginManager pluginManager;
    private final AuralisBusManager busManager;
    private final AuralisEffectRegistryImpl effectRegistry;
    private final OpenALAccessImpl openALAccess;
    private final OpenALEffectRack effectRack;
    private final AuralisVoiceManager voiceManager;
    private final float attenuationExponent;
    private final float volumeSmoothing;
    private final int streamedChunkSize;

    private final ConcurrentMap<AuralisSoundInstance, AuralisSoundInstanceImpl> instances = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingCreations = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean shutdownComplete = new AtomicBoolean(false);
    private final AtomicBoolean stopOpenALRequested = new AtomicBoolean(false);

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
        this.busManager = new AuralisBusManager();
        this.effectRegistry = new AuralisEffectRegistryImpl();
        AuralisEffects.registerBuiltIns(effectRegistry);
        this.openALAccess = new OpenALAccessImpl(al);
        this.effectRack = new OpenALEffectRack(openALAccess);
        this.pluginManager = new AuralisPluginManager(this, busManager, effectRegistry, openALAccess);
        this.voiceManager = new AuralisVoiceManager(sourcePool, voiceMaterializeGain, voiceVirtualizeGain);
        this.streamedChunkSize = streamedChunkSize;
        this.attenuationExponent = attenuationExponent;
        this.volumeSmoothing = volumeSmoothing;
    }

    public AuralisPluginManager getPluginManager() {
        return pluginManager;
    }

    @Override public AudioBusSystem buses() { return busManager; }
    @Override public AuralisEffectRegistry effects() { return effectRegistry; }
    @Override public AuralisPluginService plugins() { return pluginManager; }
    @Override public OpenALAccess openAL() { return openALAccess; }

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
        AuralisSoundInstanceImpl inst = null;

        try {
            ensureAcceptingCreations();
            ResourceLocation soundPath = resolveSoundPath(eventId);

            if (streamed) {
                var decoder = bufferCache.createStreamDecoder(soundPath);
                try {
                    inst = new AuralisSoundInstanceImpl(
                            al, decoder, streamedChunkSize, bufferCache, sourcePool, busManager, effectRack
                    );
                } catch (Throwable constructionFailure) {
                    decoder.close();
                    throw constructionFailure;
                }
            } else {
                int bufferId = bufferCache.acquireBuffer(soundPath);
                if (bufferId == -1) {
                    throw new RuntimeException("Failed to acquire valid buffer for sound: " + soundPath);
                }
                inst = new AuralisSoundInstanceImpl(al, bufferId, bufferCache, sourcePool, busManager, effectRack);
            }

            synchronized (lifecycleLock) {
                ensureAcceptingCreations();
                configureNewInstance(inst, soundEvent);
                instances.put(inst, inst);
            }
            return inst;
        } catch (Throwable failure) {
            cleanupFailedCreation(inst, failure);
            if (!shuttingDown.get()) {
                GFBsAuralis.LOGGER.error("Failed to create sound instance for: {}", eventId, failure);
            }
            return createFallbackInstance();
        }
    }

    private CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent, boolean streamed) {
        Objects.requireNonNull(soundEvent, "soundEvent");
        ResourceLocation eventId = soundEvent.getLocation();

        try {
            ensureAcceptingCreations();
            ResourceLocation soundPath = resolveSoundPath(eventId);

            CompletableFuture<AuralisSoundInstanceImpl> futureInst;
            if (streamed) {
                CompletableFuture<org.lytharalab.gfbs.auralis.utils.OggVorbisDecoder.StreamDecoder> decoderFuture =
                        bufferCache.createStreamDecoderAsync(soundPath);
                futureInst = decoderFuture
                        .thenApply(decoder -> {
                            try {
                                return new AuralisSoundInstanceImpl(
                                        al, decoder, streamedChunkSize, bufferCache, sourcePool, busManager, effectRack
                                );
                            } catch (RuntimeException | Error constructionFailure) {
                                decoder.close();
                                throw constructionFailure;
                            }
                        });
            } else {
                CompletableFuture<Integer> bufferFuture = bufferCache.acquireBufferAsync(soundPath);
                futureInst = bufferFuture
                        .thenApply(bufferId -> {
                            if (bufferId == -1) {
                                throw new RuntimeException("Failed to acquire valid buffer for sound: " + soundPath);
                            }
                            try {
                                return new AuralisSoundInstanceImpl(
                                        al, bufferId, bufferCache, sourcePool, busManager, effectRack
                                );
                            } catch (RuntimeException | Error constructionFailure) {
                                bufferCache.releaseBuffer(bufferId);
                                throw constructionFailure;
                            }
                        });
            }

            CompletableFuture<AuralisSoundInstance> result = futureInst.handle((inst, failure) -> {
                if (failure != null) {
                    if (shuttingDown.get()) throw new CancellationException("Auralis engine is shutting down");
                    GFBsAuralis.LOGGER.error("Failed to create sound instance asynchronously for: {}", eventId, failure);
                    return createFallbackInstance();
                }

                try {
                    synchronized (lifecycleLock) {
                        ensureAcceptingCreations();
                        configureNewInstance(inst, soundEvent);
                        instances.put(inst, inst);
                    }
                    return (AuralisSoundInstance) inst;
                } catch (Throwable registrationFailure) {
                    cleanupFailedCreation(inst, registrationFailure);
                    if (shuttingDown.get()) throw new CancellationException("Auralis engine is shutting down");
                    GFBsAuralis.LOGGER.error("Failed to register asynchronous sound instance for: {}", eventId, registrationFailure);
                    return createFallbackInstance();
                }
            });

            pendingCreations.add(result);
            result.whenComplete((ignored, failure) -> {
                pendingCreations.remove(result);
                if (result.isCancelled()) {
                    // CompletableFuture cancellation does not propagate ownership
                    // cleanup upstream. Let loading finish and dispose whatever it
                    // produced instead of orphaning a decoder or cache reference.
                    CancellationException cancellation = new CancellationException("Auralis sound creation was cancelled");
                    futureInst.whenComplete((inst, upstreamFailure) -> {
                        if (inst != null) cleanupFailedCreation(inst, cancellation);
                    });
                }
            });
            return result;
        } catch (Throwable failure) {
            if (!shuttingDown.get()) {
                GFBsAuralis.LOGGER.error("Failed to start sound instance creation for: {}", eventId, failure);
            }
            return CompletableFuture.completedFuture(createFallbackInstance());
        }
    }

    private void configureNewInstance(AuralisSoundInstanceImpl inst, SoundEvent soundEvent) {
        inst.replaceGlobalProcessors(pluginManager.createGlobalProcessors(), pluginManager.getProcessorRevision());
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
        if (shuttingDown.get()) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 listenerPos = cam.getPosition();
        float pitch = cam.getXRot();
        float yaw = cam.getYRot();
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 up = Vec3.directionFromRotation(pitch - 90.0F, yaw);
        long nowNanos = System.nanoTime();

        // Use a stable snapshot for this frame. Async creation may mutate the map.
        List<AuralisSoundInstanceImpl> snapshot = new ArrayList<>(instances.values());
        BusMixSnapshot busMix = busManager.snapshot();
        long globalProcessorRevision = pluginManager.getProcessorRevision();
        for (AuralisSoundInstanceImpl inst : snapshot) {
            inst.refreshBusRoute(busMix.route(inst.getBus()));
            if (inst.getGlobalProcessorRevision() != globalProcessorRevision) {
                inst.replaceGlobalProcessors(pluginManager.createGlobalProcessors(), globalProcessorRevision);
            }
            inst.refreshStaticProcessorBuffer();
        }

        // Effect objects/slots are updated once per bus snapshot, never once per
        // voice. This task is ordered before materialization and source updates.
        al.submit(() -> effectRack.syncOnALThread(busMix));

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
        if (stopOpenAL) stopOpenALRequested.set(true);
        synchronized (lifecycleLock) {
            if (!shuttingDown.compareAndSet(false, true)) {
                if (stopOpenALRequested.get() && shutdownComplete.get()) {
                    AuralisAL.stopAndClearGlobal();
                }
                return;
            }
        }

        for (CompletableFuture<?> pending : pendingCreations) {
            pending.cancel(false);
        }

        // Stop loader production while OpenAL is still available, so late results
        // can release their decoder/buffer ownership safely.
        try {
            bufferCache.shutdown();
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn("Failed to stop Auralis async loaders cleanly", failure);
        }

        for (AuralisSoundInstanceImpl inst : instances.values()) {
            try {
                inst.forceStopAndFree();
            } catch (Throwable failure) {
                GFBsAuralis.LOGGER.warn("Failed to dispose Auralis voice during shutdown", failure);
            }
        }
        instances.clear();

        // Voices are quiescent before plugin callbacks release processor/effect
        // dependencies. The OpenAL context remains available during onDisable.
        try {
            pluginManager.shutdown();
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn("Failed to shut down an Auralis plugin cleanly", failure);
        }

        try {
            effectRack.close();
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn("Failed to close the Auralis effect rack", failure);
        }

        try {
            sourcePool.close();
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn("Failed to close Auralis OpenAL source pool", failure);
        }

        try {
            bufferCache.clearAll();
            // Barrier behind all asynchronous buffer deletions submitted above.
            al.executeBlocking(() -> { });
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn("Failed to clear Auralis sound buffer cache", failure);
        }

        try {
            // Barrier behind all effect/buffer deletions before context teardown.
            al.executeBlocking(() -> { });
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.debug("OpenAL shutdown barrier was unavailable: {}", failure.getMessage());
        } finally {
            pendingCreations.clear();
            AuralisApi.clearEngine(this);
            shutdownComplete.set(true);
            if (stopOpenALRequested.get()) {
                AuralisAL.stopAndClearGlobal();
            }
        }
    }

    private void ensureAcceptingCreations() {
        if (shuttingDown.get() || shutdownComplete.get()) {
            throw new CancellationException("Auralis engine is shutting down");
        }
    }

    private AuralisSoundInstanceImpl createFallbackInstance() {
        return new AuralisSoundInstanceImpl(al, -1, bufferCache, sourcePool, busManager, effectRack);
    }

    private void cleanupFailedCreation(@Nullable AuralisSoundInstanceImpl inst, Throwable originalFailure) {
        if (inst == null) return;
        instances.remove(inst);
        try {
            inst.disposeExplicitly();
        } catch (Throwable cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
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
