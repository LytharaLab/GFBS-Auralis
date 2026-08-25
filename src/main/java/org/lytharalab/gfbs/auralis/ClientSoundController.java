package org.lytharalab.gfbs.auralis;

/**
 * Client-side sound instance registry & controller.
 * <p>
 * IMPORTANT: This class intentionally does NOT reference any client-only classes (e.g. Minecraft)
 * so that it can live in the common source set safely. It is only invoked from client-bound
 * network packet handlers.
 */

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;
import org.lytharalab.gfbs.auralis.tween.ForgeTweenHook;
import org.lytharalab.gfbs.auralis.tween.TweenInfo;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

public final class ClientSoundController {
    private ClientSoundController() {}

    private static final Map<String, AuralisSoundInstance> INSTANCES = new ConcurrentHashMap<>();
    private static final Map<String, BindTarget> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PLAY_GENERATIONS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<AuralisSoundInstance>> PENDING_CREATIONS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_PLAY_GENERATION = new AtomicLong(0L);
    private static final Object STATE_LOCK = new Object();
    private static final int MAX_PENDING_PLAY = 2048;

    public sealed interface BindTarget {
        record Entity(int entityId, UUID entityUuid) implements BindTarget {}
        record Block(BlockPos pos) implements BindTarget {}
    }
    private static final ConcurrentLinkedQueue<PendingPlay> PENDING_PLAY = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_PLAY_SIZE = new AtomicInteger(0);

    private record PendingPlay(
            String id,
            long generation,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean isStreamed
    ) {}

    public static void flushPendingIfReady() {
        if (!AuralisApi.isInitialized()) return;
        int drained = 0;
        while (drained < 64) {
            PendingPlay p = PENDING_PLAY.poll();
            if (p == null) break;
            PENDING_PLAY_SIZE.decrementAndGet();
            if (!isCurrentGeneration(p.id, p.generation)) {
                drained++;
                continue;
            }
            playInternal(
                    p.id,
                    p.generation,
                    p.soundEventId,
                    p.volume,
                    p.pitch,
                    p.speed,
                    p.isStatic,
                    p.position,
                    p.looping,
                    p.priority,
                    p.minDistance,
                    p.maxDistance,
                    p.isStreamed
            );
            drained++;
        }
    }

    private static void playInternal(
            String id,
            long generation,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean isStreamed
    ) {
        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(soundEventId);
        if (soundEvent == null) {
            PLAY_GENERATIONS.remove(id, generation);
            GFBsAuralis.LOGGER.error("[Auralis] Unknown SoundEvent id: {}", soundEventId);
            return;
        }

        startAsyncCreation(
                id,
                generation,
                soundEvent,
                volume,
                pitch,
                speed,
                isStatic,
                position,
                looping,
                priority,
                minDistance,
                maxDistance,
                isStreamed
        );
    }

    public static void play(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean isStreamed
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(soundEventId, "soundEventId");
        Objects.requireNonNull(position, "position");

        // Ensure sane distances
        float minD = Math.max(0.01f, minDistance);
        float maxD = Math.max(minD, maxDistance);

        long generation = NEXT_PLAY_GENERATION.incrementAndGet();
        AuralisSoundInstance old;
        CompletableFuture<AuralisSoundInstance> oldPending;
        synchronized (STATE_LOCK) {
            PLAY_GENERATIONS.put(id, generation);
            old = INSTANCES.remove(id);
            oldPending = PENDING_CREATIONS.remove(id);
        }
        if (oldPending != null) oldPending.cancel(false);
        disposeInstance(old);

        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(soundEventId);
        if (soundEvent == null) {
            PLAY_GENERATIONS.remove(id, generation);
            GFBsAuralis.LOGGER.error("[Auralis] Unknown SoundEvent id: {}", soundEventId);
            return;
        }

        // If engine isn't initialized yet (e.g. early login), this becomes a no-op placeholder.
        if (!AuralisApi.isInitialized()) {
            int pendingSize = PENDING_PLAY_SIZE.incrementAndGet();
            if (pendingSize <= MAX_PENDING_PLAY) {
                PENDING_PLAY.offer(new PendingPlay(
                        id,
                        generation,
                        soundEventId,
                        volume,
                        pitch,
                        speed,
                        isStatic,
                        position,
                        looping,
                        priority,
                        minD,
                        maxD,
                        isStreamed
                ));
            } else {
                PENDING_PLAY_SIZE.decrementAndGet();
                PLAY_GENERATIONS.remove(id, generation);
                GFBsAuralis.LOGGER.warn("[Auralis] Pending play queue is full; dropping sound id={}", id);
            }
            return;
        }

        startAsyncCreation(
                id,
                generation,
                soundEvent,
                volume,
                pitch,
                speed,
                isStatic,
                position,
                looping,
                priority,
                minD,
                maxD,
                isStreamed
        );
    }

    private static void startAsyncCreation(
            String id,
            long generation,
            SoundEvent soundEvent,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean isStreamed
    ) {
        final CompletableFuture<AuralisSoundInstance> creation;
        try {
            creation = isStreamed
                    ? AuralisApi.createStreamedAsync(soundEvent)
                    : AuralisApi.createAsync(soundEvent);
        } catch (Throwable startFailure) {
            clearFailedGeneration(id, generation, null);
            GFBsAuralis.LOGGER.error("[Auralis] Failed to start sound creation: {}", id, startFailure);
            return;
        }

        synchronized (STATE_LOCK) {
            if (!isCurrentGeneration(id, generation)) {
                creation.cancel(false);
                return;
            }
            PENDING_CREATIONS.put(id, creation);
        }

        creation.whenComplete((instance, failure) -> {
            if (failure != null) {
                boolean wasCurrent;
                synchronized (STATE_LOCK) {
                    PENDING_CREATIONS.remove(id, creation);
                    wasCurrent = isCurrentGeneration(id, generation);
                    if (wasCurrent) PLAY_GENERATIONS.remove(id, generation);
                }
                if (!creation.isCancelled() && wasCurrent) {
                    GFBsAuralis.LOGGER.error("[Auralis] Failed to create sound instance: {}", id, failure);
                }
                return;
            }
            if (instance == null) {
                clearFailedGeneration(id, generation, creation);
                return;
            }
            activateIfCurrent(
                    id,
                    generation,
                    creation,
                    instance,
                    volume,
                    pitch,
                    speed,
                    isStatic,
                    position,
                    looping,
                    priority,
                    minDistance,
                    maxDistance
            );
        });
    }

    public static void pause(String id) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.pause();
    }

    public static void stop(String id) {
        AuralisSoundInstance inst;
        CompletableFuture<AuralisSoundInstance> pending;
        synchronized (STATE_LOCK) {
            BINDINGS.remove(id);
            PLAY_GENERATIONS.remove(id);
            pending = PENDING_CREATIONS.remove(id);
            inst = INSTANCES.remove(id);
        }
        if (pending != null) pending.cancel(false);
        disposeInstance(inst);
    }

    public static void setVolume(String id, float volume) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setVolume(volume);
    }

    public static void setPitch(String id, float pitch) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setPitch(pitch);
    }

    public static void setSpeed(String id, float speed) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setSpeed(speed);
    }

    public static void setPosition(String id, Vec3 pos) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setPosition(pos);
    }

    public static void setStatic(String id, boolean isStatic) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setStatic(isStatic);
    }

    public static void setLooping(String id, boolean looping) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setLooping(looping);
    }

    public static void setPriority(String id, int priority) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setPriority(priority);
    }

    public static void setMinDistance(String id, float distance) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setMinDistance(Math.max(0.01f, distance));
    }

    public static void setMaxDistance(String id, float distance) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.setMaxDistance(Math.max(0.01f, distance));
    }

    public static AuralisSoundInstance getInstance(String id) {
        return INSTANCES.get(id);
    }

    /** Remove controller references after one-shot voices finish naturally. */
    public static void pruneFinishedInstances() {
        List<AuralisSoundInstance> finished = new ArrayList<>();
        synchronized (STATE_LOCK) {
            for (Map.Entry<String, AuralisSoundInstance> entry : INSTANCES.entrySet()) {
                AuralisSoundInstance instance = entry.getValue();
                if (instance.isPlaying() || instance.isPaused()) continue;
                if (INSTANCES.remove(entry.getKey(), instance)) {
                    PLAY_GENERATIONS.remove(entry.getKey());
                    BINDINGS.remove(entry.getKey());
                    finished.add(instance);
                }
            }
        }
        for (AuralisSoundInstance instance : finished) disposeInstance(instance);
    }

    public static void bindEntity(String id, int entityId, UUID entityUuid) {
        BINDINGS.put(id, new BindTarget.Entity(entityId, entityUuid));
    }

    public static void bindBlock(String id, BlockPos pos) {
        BINDINGS.put(id, new BindTarget.Block(pos));
    }

    public static void unbindSound(String id) {
        BINDINGS.remove(id);
    }

    public static void tickBoundPositions(Level level) {
        tickBoundPositions(level, null);
    }

    public static void tickBoundPositions(Level level, @Nullable Iterable<net.minecraft.world.entity.Entity> loadedEntities) {
        var it = BINDINGS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            String id = entry.getKey();
            BindTarget target = entry.getValue();
            AuralisSoundInstance inst = INSTANCES.get(id);
            if (inst == null) {
                continue;
            }
            Vec3 pos = null;
            if (target instanceof BindTarget.Entity entityTarget) {
                net.minecraft.world.entity.Entity e = null;
                UUID uuid = entityTarget.entityUuid();
                if (!uuid.equals(new UUID(0, 0)) && loadedEntities != null) {
                    for (net.minecraft.world.entity.Entity entity : loadedEntities) {
                        if (entity.getUUID().equals(uuid)) {
                            e = entity;
                            break;
                        }
                    }
                }
                if (e == null) {
                    e = level.getEntity(entityTarget.entityId());
                }
                if (e != null && e.isAlive()) {
                    pos = e.position();
                }
            } else if (target instanceof BindTarget.Block blockTarget) {
                pos = Vec3.atCenterOf(blockTarget.pos());
            }
            if (pos != null) {
                inst.setPosition(pos);
            }
        }
    }

    public static void startTween(TweenControlPacket.Property property, String id,
                                  double targetX, double targetY, double targetZ,
                                  float duration,
                                  EasingStyle easingStyle, EasingDirection easingDirection) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst == null) {
            GFBsAuralis.LOGGER.warn("[Auralis] Tween target sound not found: id={}", id);
            return;
        }

        EasingStyle style = easingStyle != null ? easingStyle : EasingStyle.LINEAR;
        EasingDirection dir = easingDirection != null ? easingDirection : EasingDirection.OUT;

        TweenInfo scalarInfo = TweenInfo.of(duration)
                .easing(style, dir)
                .build();

        switch (property) {
            case VOLUME -> ForgeTweenHook.TWEENS.create(
                    inst::getVolume,
                    v -> inst.setVolume((float) v),
                    scalarInfo,
                    targetX
            ).play();
            case PITCH -> ForgeTweenHook.TWEENS.create(
                    inst::getPitch,
                    v -> inst.setPitch((float) v),
                    scalarInfo,
                    targetX
            ).play();
            case SPEED -> ForgeTweenHook.TWEENS.create(
                    inst::getSpeed,
                    v -> inst.setSpeed((float) v),
                    scalarInfo,
                    targetX
            ).play();
            case POSITION -> {
                TweenInfo posInfo = TweenInfo.of(duration)
                        .easing(style, dir)
                        .build();
                ForgeTweenHook.TWEENS.create(
                        () -> inst.getPosition().x,
                        v -> inst.setPosition(new Vec3(v, inst.getPosition().y, inst.getPosition().z)),
                        posInfo,
                        targetX
                ).play();
                ForgeTweenHook.TWEENS.create(
                        () -> inst.getPosition().y,
                        v -> inst.setPosition(new Vec3(inst.getPosition().x, v, inst.getPosition().z)),
                        posInfo,
                        targetY
                ).play();
                ForgeTweenHook.TWEENS.create(
                        () -> inst.getPosition().z,
                        v -> inst.setPosition(new Vec3(inst.getPosition().x, inst.getPosition().y, v)),
                        posInfo,
                        targetZ
                ).play();
            }
            case MIN_DISTANCE -> ForgeTweenHook.TWEENS.create(
                    inst::getMinDistance,
                    v -> inst.setMinDistance((float) v),
                    scalarInfo,
                    targetX
            ).play();
            case MAX_DISTANCE -> ForgeTweenHook.TWEENS.create(
                    inst::getMaxDistance,
                    v -> inst.setMaxDistance((float) v),
                    scalarInfo,
                    targetX
            ).play();
        }
    }

    public static void stopAll() {
        List<CompletableFuture<AuralisSoundInstance>> pending;
        List<AuralisSoundInstance> active;
        synchronized (STATE_LOCK) {
            BINDINGS.clear();
            PLAY_GENERATIONS.clear();
            PENDING_PLAY.clear();
            PENDING_PLAY_SIZE.set(0);
            pending = new ArrayList<>(PENDING_CREATIONS.values());
            PENDING_CREATIONS.clear();
            active = new ArrayList<>(INSTANCES.values());
            INSTANCES.clear();
        }
        for (CompletableFuture<AuralisSoundInstance> creation : pending) creation.cancel(false);
        for (AuralisSoundInstance inst : active) disposeInstance(inst);
    }

    private static void activateIfCurrent(
            String id,
            long generation,
            @Nullable CompletableFuture<AuralisSoundInstance> creation,
            AuralisSoundInstance instance,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance
    ) {
        if (instance instanceof AuralisSoundInstanceImpl impl && !impl.hasPlayableResource()) {
            clearFailedGeneration(id, generation, creation);
            disposeInstance(instance);
            GFBsAuralis.LOGGER.warn("[Auralis] Sound instance has no playable audio resource: {}", id);
            return;
        }

        try {
            instance
                    .setVolume(volume)
                    .setPitch(pitch)
                    .setSpeed(speed)
                    .setStatic(isStatic)
                    .setPosition(position)
                    .setLooping(looping)
                    .setPriority(priority)
                    .setMinDistance(minDistance)
                    .setMaxDistance(maxDistance);
        } catch (Throwable configurationFailure) {
            clearFailedGeneration(id, generation, creation);
            disposeInstance(instance);
            GFBsAuralis.LOGGER.error("[Auralis] Failed to configure sound instance: {}", id, configurationFailure);
            return;
        }

        boolean stale;
        synchronized (STATE_LOCK) {
            stale = !isCurrentGeneration(id, generation)
                    || (creation != null && PENDING_CREATIONS.get(id) != creation);
            if (creation != null) PENDING_CREATIONS.remove(id, creation);
        }

        if (stale) {
            disposeInstance(instance);
            return;
        }

        Throwable activationFailure = null;
        try {
            AuralisSoundInstance.bind(instance);
            instance.play();
        } catch (Throwable failure) {
            activationFailure = failure;
        }

        synchronized (STATE_LOCK) {
            stale = activationFailure != null || !isCurrentGeneration(id, generation);
            if (stale) {
                PLAY_GENERATIONS.remove(id, generation);
            } else {
                INSTANCES.put(id, instance);
            }
        }

        if (stale) disposeInstance(instance);
        if (activationFailure != null) {
            GFBsAuralis.LOGGER.error("[Auralis] Failed to activate sound instance: {}", id, activationFailure);
        }
    }

    private static boolean isCurrentGeneration(String id, long generation) {
        Long current = PLAY_GENERATIONS.get(id);
        return current != null && current == generation;
    }

    private static void clearFailedGeneration(
            String id,
            long generation,
            @Nullable CompletableFuture<AuralisSoundInstance> creation
    ) {
        synchronized (STATE_LOCK) {
            if (creation != null) PENDING_CREATIONS.remove(id, creation);
            PLAY_GENERATIONS.remove(id, generation);
        }
    }

    private static void disposeInstance(@Nullable AuralisSoundInstance instance) {
        if (instance == null) return;
        try {
            instance.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (instance instanceof AuralisSoundInstanceImpl impl && impl.isDisposed()) return;
            if (AuralisApi.isInitialized()) {
                AuralisSoundInstance.unbind(instance);
            } else if (instance instanceof AuralisSoundInstanceImpl directInstance) {
                directInstance.disposeExplicitly();
            }
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.debug("[Auralis] Failed to dispose sound instance cleanly: {}", failure.getMessage());
        }
    }
}
