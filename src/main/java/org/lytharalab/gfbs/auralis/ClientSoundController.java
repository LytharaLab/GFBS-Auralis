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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

public final class ClientSoundController {
    private ClientSoundController() {}

    private static final Map<String, AuralisSoundInstance> INSTANCES = new ConcurrentHashMap<>();
    private static final Map<String, BindTarget> BINDINGS = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_PLAY = 2048;

    public sealed interface BindTarget {
        record Entity(int entityId, UUID entityUuid) implements BindTarget {}
        record Block(BlockPos pos) implements BindTarget {}
    }
    private static final ConcurrentLinkedQueue<PendingPlay> PENDING_PLAY = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_PLAY_SIZE = new AtomicInteger(0);

    private record PendingPlay(
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
    ) {}

    public static void flushPendingIfReady() {
        if (!AuralisApi.isInitialized()) return;
        int drained = 0;
        while (drained < 64) {
            PendingPlay p = PENDING_PLAY.poll();
            if (p == null) break;
            PENDING_PLAY_SIZE.decrementAndGet();
            playInternal(
                    p.id,
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
            GFBsAuralis.LOGGER.error("[Auralis] Unknown SoundEvent id: {}", soundEventId);
            return;
        }

        AuralisSoundInstance instance;
        if (isStreamed) {
            instance = AuralisApi.createStreamed(soundEvent);
        } else {
            instance = AuralisApi.create(soundEvent);
        }
        
        if (instance != null) {
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

            AuralisSoundInstance.bind(instance);
            instance.play();
            INSTANCES.put(id, instance);
        }
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

        // Stop+unbind any previous instance with same id
        AuralisSoundInstance old = INSTANCES.remove(id);
        if (old != null) {
            try {
                old.stop();
            } catch (Throwable ignored) {}
            try {
                AuralisSoundInstance.unbind(old);
            } catch (Throwable ignored) {}
        }

        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(soundEventId);
        if (soundEvent == null) {
            GFBsAuralis.LOGGER.error("[Auralis] Unknown SoundEvent id: {}", soundEventId);
            return;
        }

        // If engine isn't initialized yet (e.g. early login), this becomes a no-op placeholder.
        if (!AuralisApi.isInitialized()) {
            if (PENDING_PLAY_SIZE.get() < MAX_PENDING_PLAY) {
                PENDING_PLAY.offer(new PendingPlay(
                        id,
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
                PENDING_PLAY_SIZE.incrementAndGet();
            }
            return;
        }

        if (isStreamed) {
            AuralisApi.createStreamedAsync(soundEvent)
                    .thenAccept(instance -> {
                        if (instance == null) return;
                        
                        instance
                                .setVolume(volume)
                                .setPitch(pitch)
                                .setSpeed(speed)
                                .setStatic(isStatic)
                                .setPosition(position)
                                .setLooping(looping)
                                .setPriority(priority)
                                .setMinDistance(minD)
                                .setMaxDistance(maxD);

                        AuralisSoundInstance.bind(instance);
                        instance.play();
                        INSTANCES.put(id, instance);
                    })
                    .exceptionally(ex -> {
                        GFBsAuralis.LOGGER.error("[Auralis] Failed to create streamed sound instance: {}", id, ex);
                        return null;
                    });
        } else {
            AuralisApi.createAsync(soundEvent)
                    .thenAccept(instance -> {
                        if (instance == null) return;
                        
                        instance
                                .setVolume(volume)
                                .setPitch(pitch)
                                .setSpeed(speed)
                                .setStatic(isStatic)
                                .setPosition(position)
                                .setLooping(looping)
                                .setPriority(priority)
                                .setMinDistance(minD)
                                .setMaxDistance(maxD);

                        AuralisSoundInstance.bind(instance);
                        instance.play();
                        INSTANCES.put(id, instance);
                    })
                    .exceptionally(ex -> {
                        GFBsAuralis.LOGGER.error("[Auralis] Failed to create sound instance: {}", id, ex);
                        return null;
                    });
        }
    }

    public static void pause(String id) {
        AuralisSoundInstance inst = INSTANCES.get(id);
        if (inst != null) inst.pause();
    }

    public static void stop(String id) {
        BINDINGS.remove(id);
        AuralisSoundInstance inst = INSTANCES.remove(id);
        if (inst == null) return;
        try {
            inst.stop();
        } finally {
            try {
                AuralisSoundInstance.unbind(inst);
            } catch (Throwable ignored) {}
        }
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
        BINDINGS.clear();
        // Clear pending plays first
        PENDING_PLAY.clear();
        PENDING_PLAY_SIZE.set(0);

        // Stop all active instances
        for (AuralisSoundInstance inst : INSTANCES.values()) {
            try {
                inst.stop();
            } catch (Throwable ignored) {}
            try {
                AuralisSoundInstance.unbind(inst);
            } catch (Throwable ignored) {}
        }
        INSTANCES.clear();
    }
}
