package org.lytharalab.gfbs.auralis.server;
/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 * <p>
 * This program is licensed under the MIT License.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.GFBsAuralisConfig;
import org.lytharalab.gfbs.auralis.api.AuralisAudience;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.network.AudioBusStatePacket;
import org.lytharalab.gfbs.auralis.network.AudioStateDeltaPacket;
import org.lytharalab.gfbs.auralis.network.AudioStateSnapshotPacket;
import org.lytharalab.gfbs.auralis.network.BusControlPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.network.sync.SyncedBusState;
import org.lytharalab.gfbs.auralis.network.sync.SyncedSoundState;
import org.lytharalab.gfbs.auralis.network.sync.SyncedTweenState;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

/**
 * Server-authoritative logical audio runtime.
 *
 * <p>Instances are immutable timestamped records. They do not own threads and
 * they do not tick on the Minecraft server thread. One shared daemon scheduler
 * is used only for known-duration one-shot expiry and asynchronous snapshots.</p>
 */
public final class AuralisServerManager {
    private static final int MAX_BUSES_PER_SCOPE = 256;
    private static final int MAX_BUS_STATE_ENTRIES_PER_SCOPE = 512;
    private static final long CLIENT_SNAPSHOT_INTERVAL_NANOS = 1_000_000_000L;
    private static final ReentrantReadWriteLock STATE_LOCK = new ReentrantReadWriteLock();
    private static final AtomicLong NEXT_REVISION = new AtomicLong(0L);
    private static final AtomicLong NEXT_SNAPSHOT = new AtomicLong(0L);

    private static final Map<String, SoundEntry> GLOBAL_SOUNDS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<String, SoundEntry>> DIMENSION_SOUNDS = new HashMap<>();
    private static final Map<UUID, Map<String, SoundEntry>> PLAYER_SOUNDS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> DIMENSION_LIVE_SOUND_COUNTS = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_LIVE_SOUND_COUNTS = new HashMap<>();
    private static final Map<String, BusEntry> GLOBAL_BUSES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<String, BusEntry>> DIMENSION_BUSES = new HashMap<>();
    private static final Map<UUID, Map<String, BusEntry>> PLAYER_BUSES = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> PLAYER_DIMENSIONS = new HashMap<>();

    private static final Map<ScopedSoundKey, ScheduledFuture<?>> EXPIRY_TASKS = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING_SNAPSHOTS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_CLIENT_SNAPSHOT_REQUEST = new ConcurrentHashMap<>();
    private static volatile ScheduledThreadPoolExecutor runtimeExecutor;
    private static volatile MinecraftServer currentServer;
    private static volatile UUID serverEpoch = UUID.randomUUID();
    private static int globalLiveSoundCount;
    private static int maximumDimensionLiveSoundCount;
    private static int maximumPlayerLiveSoundCount;

    private AuralisServerManager() {
    }

    public static void onServerStart(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        STATE_LOCK.writeLock().lock();
        try {
            currentServer = server;
            serverEpoch = UUID.randomUUID();
            NEXT_REVISION.set(0L);
            NEXT_SNAPSHOT.set(0L);
            clearStateLocked();
            ensureExecutorLocked();
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        GFBsAuralis.LOGGER.info(
                "Auralis authoritative runtime started (epoch={}, sharedWorkers=1)", serverEpoch
        );
    }

    public static int playSound(
            AuralisAudience audience,
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean staticSound,
            Vec3 position,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean streamed,
        double durationSeconds
    ) {
        Objects.requireNonNull(audience, "audience");
        long now = System.nanoTime();

        STATE_LOCK.writeLock().lock();
        try {
            ensureRuntimeLocked();
            long revision = nextRevision();
            SyncedSoundState state = new SyncedSoundState.Builder(id, soundEventId, now)
                    .revision(revision)
                    .streamed(streamed)
                    .volume(volume)
                    .pitch(pitch)
                    .speed(speed)
                    .staticSound(staticSound)
                    .position(Objects.requireNonNull(position, "position"))
                    .looping(looping)
                    .priority(priority)
                    .minDistance(minDistance)
                    .maxDistance(maxDistance)
                    .durationSeconds(durationSeconds)
                    .build();
            for (ScopeRef scope : scopes(audience)) {
                if (putSoundLocked(scope, id, SoundEntry.state(state))) {
                    scheduleExpiryLocked(scope, state, now);
                }
            }
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        return notifySoundChange(audience, id);
    }

    public static int pauseSound(AuralisAudience audience, String id) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .playbackStatus(SyncedSoundState.PlaybackStatus.PAUSED)
                .build());
    }

    public static int resumeSound(AuralisAudience audience, String id) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .playbackStatus(SyncedSoundState.PlaybackStatus.PLAYING)
                .build());
    }

    public static int stopSound(AuralisAudience audience, String id) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(id, "id");
        STATE_LOCK.writeLock().lock();
        try {
            ensureRuntimeLocked();
            long revision = nextRevision();
            for (ScopeRef scope : scopes(audience)) {
                Map<String, SoundEntry> map = soundMapLocked(scope);
                compactSoundTombstonesLocked(scope, map);
                SoundEntry previous = map.get(id);
                if (hasPotentiallyMaskedSoundLocked(scope, id, revision)) {
                    map.put(id, SoundEntry.tombstone(revision));
                } else {
                    // A stop for an unknown id needs a delta for current clients,
                    // but retaining it cannot mask any older layer. Future plays
                    // always have a higher revision, so no tombstone is needed.
                    map.remove(id);
                }
                if (previous != null && previous.state != null) {
                    decrementLiveSoundCountLocked(scope);
                }
                cancelExpiryLocked(new ScopedSoundKey(scope, id));
            }
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        return notifySoundChange(audience, id);
    }

    public static int setVolume(AuralisAudience audience, String id, float volume) {
        return setScalar(audience, id, TweenControlPacket.Property.VOLUME, volume);
    }

    public static int setPitch(AuralisAudience audience, String id, float pitch) {
        return setScalar(audience, id, TweenControlPacket.Property.PITCH, pitch);
    }

    public static int setSpeed(AuralisAudience audience, String id, float speed) {
        return setScalar(audience, id, TweenControlPacket.Property.SPEED, speed);
    }

    public static int setMinDistance(AuralisAudience audience, String id, float distance) {
        return setScalar(audience, id, TweenControlPacket.Property.MIN_DISTANCE, distance);
    }

    public static int setMaxDistance(AuralisAudience audience, String id, float distance) {
        return setScalar(audience, id, TweenControlPacket.Property.MAX_DISTANCE, distance);
    }

    public static int setPosition(AuralisAudience audience, String id, Vec3 position) {
        Objects.requireNonNull(position, "position");
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .position(position)
                .removeTween(TweenControlPacket.Property.POSITION)
                .build());
    }

    public static int setStatic(AuralisAudience audience, String id, boolean staticSound) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .staticSound(staticSound)
                .build());
    }

    public static int setLooping(AuralisAudience audience, String id, boolean looping) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .looping(looping)
                .build());
    }

    public static int setPriority(AuralisAudience audience, String id, int priority) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .priority(priority)
                .build());
    }

    public static int setBus(AuralisAudience audience, String id, String busName) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .busName(busName)
                .build());
    }

    public static int bindEntity(AuralisAudience audience, String id, int entityId, UUID entityUuid) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .bindEntity(entityId, entityUuid)
                .build());
    }

    public static int bindBlock(AuralisAudience audience, String id, BlockPos position) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .bindBlock(position)
                .build());
    }

    public static int unbind(AuralisAudience audience, String id) {
        return mutateSound(audience, id, (state, revision) -> state.toBuilder()
                .revision(revision)
                .unbind()
                .build());
    }

    public static int tween(
            AuralisAudience audience,
            String id,
            TweenControlPacket.Property property,
            double targetX,
            double targetY,
            double targetZ,
            float durationSeconds,
            EasingStyle easingStyle,
            EasingDirection easingDirection
    ) {
        Objects.requireNonNull(property, "property");
        if (!Float.isFinite(durationSeconds) || durationSeconds <= 0.0f) {
            return property == TweenControlPacket.Property.POSITION
                    ? setPosition(audience, id, new Vec3(targetX, targetY, targetZ))
                    : setScalar(audience, id, property, (float) targetX);
        }
        long now = System.nanoTime();
        long durationNanos = Math.max(1L, (long) (durationSeconds * 1_000_000_000.0));
        return mutateSoundAt(audience, id, now, (state, revision) -> {
            double startX;
            double startY = 0.0;
            double startZ = 0.0;
            if (property == TweenControlPacket.Property.POSITION) {
                Vec3 current = state.positionAt(now);
                startX = current.x;
                startY = current.y;
                startZ = current.z;
            } else {
                startX = scalarValue(state, property, now);
            }
            SyncedTweenState tween = new SyncedTweenState(
                    property,
                    startX, startY, startZ,
                    targetX, targetY, targetZ,
                    now, durationNanos,
                    easingStyle, easingDirection
            );
            return state.toBuilder().revision(revision).replaceTween(tween).build();
        });
    }

    public static int applyBusControl(AuralisAudience audience, BusControlPacket packet) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(packet, "packet");
        String target = packet.target().trim();
        String parent = packet.parent().trim();
        if (packet.action() == BusControlPacket.Action.SET_INSTANCE_BUS) {
            return setBus(audience, target, parent);
        }

        boolean changed = false;
        STATE_LOCK.writeLock().lock();
        try {
            ensureRuntimeLocked();
            long revision = nextRevision();
            for (ScopeRef scope : scopes(audience)) {
                SyncedBusState base = effectiveBusForScopeLocked(scope, target);
                if (base == null && AudioBusSystem.MASTER.equals(target)) {
                    base = SyncedBusState.create(
                            revision, AudioBusSystem.MASTER, AudioBusSystem.MASTER
                    );
                }
                if (base == null
                        && packet.action() == BusControlPacket.Action.REMOVE_BUS
                        && hasPotentiallyMaskedBusLocked(scope, target, revision)) {
                    base = SyncedBusState.create(
                            revision, target, AudioBusSystem.MASTER
                    );
                }
                if (packet.action() != BusControlPacket.Action.CREATE_BUS
                        && (base == null || base.removed())) {
                    continue;
                }
                if ((packet.action() == BusControlPacket.Action.CREATE_BUS
                        || packet.action() == BusControlPacket.Action.REMOVE_BUS
                        || packet.action() == BusControlPacket.Action.SET_PARENT)
                        && AudioBusSystem.MASTER.equals(target)) {
                    continue;
                }
                if ((packet.action() == BusControlPacket.Action.CREATE_BUS
                        || packet.action() == BusControlPacket.Action.SET_PARENT)
                        && !validBusParentLocked(scope, target, parent)) {
                    GFBsAuralis.LOGGER.warn(
                            "Rejected invalid authoritative Auralis bus route {} -> {}",
                            target, parent
                    );
                    continue;
                }
                SyncedBusState updated = switch (packet.action()) {
                    case CREATE_BUS -> SyncedBusState.create(revision, target, parent);
                    case REMOVE_BUS -> new SyncedBusState(
                            revision, base.name(), base.parent(), base.volume(),
                            base.muted(), base.solo(), base.effectsBypassed(), true
                    );
                    case SET_PARENT -> new SyncedBusState(
                            revision, base.name(), parent, base.volume(),
                            base.muted(), base.solo(), base.effectsBypassed(), false
                    );
                    case SET_VOLUME -> new SyncedBusState(
                            revision, base.name(), base.parent(), packet.value(),
                            base.muted(), base.solo(), base.effectsBypassed(), false
                    );
                    case SET_MUTED -> new SyncedBusState(
                            revision, base.name(), base.parent(), base.volume(),
                            packet.flag(), base.solo(), base.effectsBypassed(), false
                    );
                    case SET_SOLO -> new SyncedBusState(
                            revision, base.name(), base.parent(), base.volume(),
                            base.muted(), packet.flag(), base.effectsBypassed(), false
                    );
                    case SET_EFFECTS_BYPASSED -> new SyncedBusState(
                            revision, base.name(), base.parent(), base.volume(),
                            base.muted(), base.solo(), packet.flag(), false
                    );
                    case SET_INSTANCE_BUS -> throw new IllegalStateException("handled above");
                };
                changed |= putBusLocked(scope, updated.name(), new BusEntry(updated));
            }
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        return changed ? notifyBusChange(audience, target) : 0;
    }

    public static void sendSnapshotAsync(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!PENDING_SNAPSHOTS.add(playerId)) return;
        MinecraftServer server = server();
        if (server == null) {
            PENDING_SNAPSHOTS.remove(playerId);
            return;
        }
        try {
            server.execute(() -> captureAndQueueSnapshot(server, playerId));
        } catch (RuntimeException rejected) {
            PENDING_SNAPSHOTS.remove(playerId);
            GFBsAuralis.LOGGER.debug("Auralis snapshot request rejected during shutdown");
        }
    }

    /** Rate-limited entry point for untrusted client snapshot requests. */
    public static void requestSnapshotFromClient(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        long now = System.nanoTime();
        Long previous = LAST_CLIENT_SNAPSHOT_REQUEST.put(playerId, now);
        if (previous != null && now - previous >= 0L
                && now - previous < CLIENT_SNAPSHOT_INTERVAL_NANOS) {
            return;
        }
        sendSnapshotAsync(playerId);
    }

    /** Resolve Minecraft-owned player state on the server thread, then hand the
     * bounded snapshot calculation to the shared Auralis worker. */
    private static void captureAndQueueSnapshot(MinecraftServer expectedServer, UUID playerId) {
        if (server() != expectedServer) {
            PENDING_SNAPSHOTS.remove(playerId);
            return;
        }
        ServerPlayer player = expectedServer.getPlayerList().getPlayer(playerId);
        if (player == null || player.hasDisconnected()) {
            PENDING_SNAPSHOTS.remove(playerId);
            return;
        }
        ResourceKey<Level> dimension = player.level().dimension();
        rememberPlayerDimension(playerId, dimension);

        ScheduledThreadPoolExecutor executor = runtimeExecutor;
        if (executor == null || executor.isShutdown()) {
            PENDING_SNAPSHOTS.remove(playerId);
            return;
        }
        SnapshotTarget target = new SnapshotTarget(playerId, dimension);
        try {
            executor.execute(() -> {
                try {
                    sendSnapshot(target);
                } finally {
                    PENDING_SNAPSHOTS.remove(playerId);
                }
            });
        } catch (RuntimeException rejected) {
            PENDING_SNAPSHOTS.remove(playerId);
            GFBsAuralis.LOGGER.debug("Auralis snapshot calculation rejected during shutdown");
        }
    }

    private static void rememberPlayerDimension(ServerPlayer player) {
        rememberPlayerDimension(player.getUUID(), player.level().dimension());
    }

    private static void rememberPlayerDimension(UUID playerId, ResourceKey<Level> dimension) {
        STATE_LOCK.writeLock().lock();
        try {
            PLAYER_DIMENSIONS.put(playerId, dimension);
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
    }

    public static void syncAllSoundsToPlayer(ServerPlayer player) {
        if (player == null) return;
        rememberPlayerDimension(player);
        sendSnapshotAsync(player.getUUID());
    }

    public static void onPlayerLogin(ServerPlayer player) {
        if (player == null) return;
        rememberPlayerDimension(player);
        ScheduledThreadPoolExecutor executor;
        STATE_LOCK.writeLock().lock();
        try {
            ensureRuntimeLocked();
            executor = runtimeExecutor;
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        if (executor != null && !executor.isShutdown()) {
            executor.schedule(() -> sendSnapshotAsync(player.getUUID()), 2L, TimeUnit.SECONDS);
        }
    }

    public static void onPlayerLogout(ServerPlayer player) {
        if (player == null) return;
        LAST_CLIENT_SNAPSHOT_REQUEST.remove(player.getUUID());
        PENDING_SNAPSHOTS.remove(player.getUUID());
        STATE_LOCK.writeLock().lock();
        try {
            // UUID-scoped audio state deliberately survives a transient reconnect;
            // only the now-stale dimension observation is removed.
            PLAYER_DIMENSIONS.remove(player.getUUID());
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
    }

    public static void onServerStop() {
        ScheduledThreadPoolExecutor executor;
        STATE_LOCK.writeLock().lock();
        try {
            executor = runtimeExecutor;
            runtimeExecutor = null;
            currentServer = null;
            clearStateLocked();
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* Legacy client-sync packet compatibility. */
    public static void playSound(
            ServerPlayer player,
            ResourceLocation soundId,
            float volume,
            float pitch,
            boolean looping
    ) {
        playSound(
                AuralisAudience.player(player), legacyId(soundId), soundId,
                volume, pitch, 1.0f, true, Vec3.ZERO, looping,
                50, 0.1f, 0.1f, false, 0.0
        );
    }

    public static void syncSound(
            ServerPlayer player,
            ResourceLocation soundId,
            float volume,
            float pitch,
            boolean looping
    ) {
        playSound(player, soundId, volume, pitch, looping);
    }

    public static void stopSound(ServerPlayer player, ResourceLocation soundId) {
        stopSound(AuralisAudience.player(player), legacyId(soundId));
    }

    public static void setLooping(ServerPlayer player, ResourceLocation soundId, boolean looping) {
        setLooping(AuralisAudience.player(player), legacyId(soundId), looping);
    }

    private static int setScalar(
            AuralisAudience audience,
            String id,
            TweenControlPacket.Property property,
            float value
    ) {
        return mutateSound(audience, id, (state, revision) -> {
            SyncedSoundState.Builder builder = state.toBuilder().revision(revision).removeTween(property);
            switch (property) {
                case VOLUME -> builder.volume(value);
                case PITCH -> builder.pitch(value);
                case SPEED -> builder.speed(value);
                case MIN_DISTANCE -> builder.minDistance(value);
                case MAX_DISTANCE -> builder.maxDistance(value);
                case POSITION -> throw new IllegalArgumentException("Use setPosition for position values");
            }
            return builder.build();
        });
    }

    private static int mutateSound(
            AuralisAudience audience,
            String id,
            BiFunction<SyncedSoundState, Long, SyncedSoundState> mutation
    ) {
        return mutateSoundAt(audience, id, System.nanoTime(), mutation);
    }

    private static int mutateSoundAt(
            AuralisAudience audience,
            String id,
            long now,
            BiFunction<SyncedSoundState, Long, SyncedSoundState> mutation
    ) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(id, "id");
        boolean changed = false;
        STATE_LOCK.writeLock().lock();
        try {
            ensureRuntimeLocked();
            long revision = nextRevision();
            for (ScopeRef scope : scopes(audience)) {
                SyncedSoundState base = effectiveSoundForScopeLocked(scope, id);
                if (base == null) continue;
                SyncedSoundState updated = Objects.requireNonNull(
                        mutation.apply(base.materialize(now), revision), "sound mutation"
                );
                if (putSoundLocked(scope, id, SoundEntry.state(updated))) {
                    scheduleExpiryLocked(scope, updated, now);
                    changed = true;
                }
            }
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        return changed ? notifySoundChange(audience, id) : 0;
    }

    private static double scalarValue(
            SyncedSoundState state,
            TweenControlPacket.Property property,
            long now
    ) {
        return switch (property) {
            case VOLUME -> state.volumeAt(now);
            case PITCH -> state.pitchAt(now);
            case SPEED -> state.speedAt(now);
            case MIN_DISTANCE -> state.minDistanceAt(now);
            case MAX_DISTANCE -> state.maxDistanceAt(now);
            case POSITION -> throw new IllegalArgumentException("Position is not scalar");
        };
    }

    private static int notifySoundChange(AuralisAudience audience, String id) {
        return notifySoundChange(audience, id, -1L);
    }

    private static int notifySoundChange(
            AuralisAudience audience,
            String id,
            long minimumRemovalRevision
    ) {
        List<ServerPlayer> recipients = recipients(audience);
        UUID epoch = serverEpoch;
        long sendNanos = System.nanoTime();
        int sent = 0;
        for (ServerPlayer player : recipients) {
            SoundEntry effective;
            STATE_LOCK.readLock().lock();
            try {
                effective = effectiveSoundLocked(player, id);
            } finally {
                STATE_LOCK.readLock().unlock();
            }
            if (effective == null || effective.state == null) {
                long revision = effective == null ? NEXT_REVISION.get() : effective.revision;
                revision = Math.max(revision, minimumRemovalRevision);
                send(player, AudioStateDeltaPacket.remove(epoch, id, revision, sendNanos));
            } else {
                send(player, AudioStateDeltaPacket.upsert(epoch, effective.state, sendNanos));
            }
            sent++;
        }
        return sent;
    }

    private static int notifyBusChange(AuralisAudience audience, String name) {
        List<ServerPlayer> recipients = recipients(audience);
        int sent = 0;
        for (ServerPlayer player : recipients) {
            BusEntry effective;
            STATE_LOCK.readLock().lock();
            try {
                effective = effectiveBusLocked(player, name);
            } finally {
                STATE_LOCK.readLock().unlock();
            }
            if (effective == null) continue;
            send(player, new AudioBusStatePacket(serverEpoch, effective.state, System.nanoTime()));
            sent++;
        }
        return sent;
    }

    private static void sendSnapshot(SnapshotTarget target) {
        long now = System.nanoTime();
        long upperRevision;
        UUID epoch;
        List<SyncedBusState> buses;
        List<SyncedSoundState> sounds;
        STATE_LOCK.readLock().lock();
        try {
            upperRevision = NEXT_REVISION.get();
            epoch = serverEpoch;
            buses = effectiveBusesLocked(target.playerId, target.dimension);
            sounds = effectiveSoundsLocked(target.playerId, target.dimension, now);
        } finally {
            STATE_LOCK.readLock().unlock();
        }

        buses = buses.stream().sorted(Comparator.comparingLong(SyncedBusState::revision)).toList();
        sounds = sounds.stream()
                .sorted(Comparator.comparingInt(SyncedSoundState::priority).reversed()
                        .thenComparing(Comparator.comparingLong(SyncedSoundState::revision).reversed()))
                .limit(maxSoundsPerPlayer())
                .toList();

        List<SnapshotEntry> entries = new ArrayList<>(buses.size() + sounds.size());
        for (SyncedBusState bus : buses) entries.add(new SnapshotEntry(bus, null));
        for (SyncedSoundState sound : sounds) entries.add(new SnapshotEntry(null, sound));
        int entryLimit = AudioStateSnapshotPacket.MAX_ENTRIES_PER_CHUNK
                * AudioStateSnapshotPacket.MAX_CHUNKS;
        if (entries.size() > entryLimit) entries = entries.subList(0, entryLimit);

        int chunkSize = AudioStateSnapshotPacket.MAX_ENTRIES_PER_CHUNK;
        int chunkCount = Math.max(1, (entries.size() + chunkSize - 1) / chunkSize);
        long snapshotId = NEXT_SNAPSHOT.incrementAndGet();
        List<AudioStateSnapshotPacket> packets = new ArrayList<>(chunkCount);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int from = chunkIndex * chunkSize;
            int to = Math.min(entries.size(), from + chunkSize);
            List<SyncedBusState> chunkBuses = new ArrayList<>();
            List<SyncedSoundState> chunkSounds = new ArrayList<>();
            for (int index = from; index < to; index++) {
                SnapshotEntry entry = entries.get(index);
                if (entry.bus != null) chunkBuses.add(entry.bus);
                if (entry.sound != null) chunkSounds.add(entry.sound);
            }
            packets.add(new AudioStateSnapshotPacket(
                    epoch, snapshotId, upperRevision, System.nanoTime(),
                    chunkIndex, chunkCount, chunkBuses, chunkSounds
            ));
        }
        MinecraftServer server = server();
        if (server == null) return;
        try {
            server.execute(() -> {
                if (AuralisServerManager.server() != server) return;
                ServerPlayer player = server.getPlayerList().getPlayer(target.playerId);
                if (player == null || player.hasDisconnected()) return;
                for (AudioStateSnapshotPacket packet : packets) send(player, packet);
            });
        } catch (RuntimeException rejected) {
            GFBsAuralis.LOGGER.debug("Auralis snapshot delivery rejected during shutdown");
        }
    }

    private static List<SyncedSoundState> effectiveSoundsLocked(
            UUID playerId,
            ResourceKey<Level> dimensionKey,
            long now
    ) {
        Set<String> ids = new LinkedHashSet<>(GLOBAL_SOUNDS.keySet());
        Map<String, SoundEntry> dimension = DIMENSION_SOUNDS.get(dimensionKey);
        Map<String, SoundEntry> personal = PLAYER_SOUNDS.get(playerId);
        if (dimension != null) ids.addAll(dimension.keySet());
        if (personal != null) ids.addAll(personal.keySet());
        List<SyncedSoundState> result = new ArrayList<>();
        for (String id : ids) {
            SoundEntry entry = effectiveSoundLocked(playerId, dimensionKey, id);
            if (entry == null || entry.state == null) continue;
            SyncedSoundState state = entry.state.materialize(now);
            if (!state.isKnownComplete(now)) result.add(state);
        }
        return result;
    }

    private static List<SyncedBusState> effectiveBusesLocked(
            UUID playerId,
            ResourceKey<Level> dimensionKey
    ) {
        Set<String> names = new LinkedHashSet<>(GLOBAL_BUSES.keySet());
        Map<String, BusEntry> dimension = DIMENSION_BUSES.get(dimensionKey);
        Map<String, BusEntry> personal = PLAYER_BUSES.get(playerId);
        if (dimension != null) names.addAll(dimension.keySet());
        if (personal != null) names.addAll(personal.keySet());
        List<SyncedBusState> result = new ArrayList<>();
        for (String name : names) {
            BusEntry entry = effectiveBusLocked(playerId, dimensionKey, name);
            if (entry != null) result.add(entry.state);
        }
        return normalizeBusParents(result);
    }

    private static SoundEntry effectiveSoundLocked(ServerPlayer player, String id) {
        return effectiveSoundLocked(player.getUUID(), player.level().dimension(), id);
    }

    private static SoundEntry effectiveSoundLocked(
            UUID playerId,
            ResourceKey<Level> dimensionKey,
            String id
    ) {
        SoundEntry best = GLOBAL_SOUNDS.get(id);
        Map<String, SoundEntry> dimension = DIMENSION_SOUNDS.get(dimensionKey);
        if (dimension != null) best = newerSound(best, dimension.get(id));
        Map<String, SoundEntry> personal = PLAYER_SOUNDS.get(playerId);
        if (personal != null) best = newerSound(best, personal.get(id));
        return best;
    }

    private static SyncedSoundState effectiveSoundForScopeLocked(ScopeRef scope, String id) {
        SoundEntry best = effectiveSoundEntryForScopeLocked(scope, id);
        return best == null ? null : best.state;
    }

    private static SoundEntry effectiveSoundEntryForScopeLocked(ScopeRef scope, String id) {
        SoundEntry best = GLOBAL_SOUNDS.get(id);
        if (scope.kind == ScopeKind.DIMENSION) {
            Map<String, SoundEntry> dimension = DIMENSION_SOUNDS.get(scope.dimension());
            if (dimension != null) best = newerSound(best, dimension.get(id));
        } else if (scope.kind == ScopeKind.PLAYER) {
            ResourceKey<Level> dimensionKey = PLAYER_DIMENSIONS.get(scope.playerId());
            if (dimensionKey != null) {
                Map<String, SoundEntry> dimension = DIMENSION_SOUNDS.get(dimensionKey);
                if (dimension != null) best = newerSound(best, dimension.get(id));
            }
            Map<String, SoundEntry> personal = PLAYER_SOUNDS.get(scope.playerId());
            if (personal != null) best = newerSound(best, personal.get(id));
        }
        return best;
    }

    private static BusEntry effectiveBusLocked(ServerPlayer player, String name) {
        return effectiveBusLocked(player.getUUID(), player.level().dimension(), name);
    }

    private static BusEntry effectiveBusLocked(
            UUID playerId,
            ResourceKey<Level> dimensionKey,
            String name
    ) {
        BusEntry best = GLOBAL_BUSES.get(name);
        Map<String, BusEntry> dimension = DIMENSION_BUSES.get(dimensionKey);
        if (dimension != null) best = newerBus(best, dimension.get(name));
        Map<String, BusEntry> personal = PLAYER_BUSES.get(playerId);
        if (personal != null) best = newerBus(best, personal.get(name));
        return best;
    }

    private static SyncedBusState effectiveBusForScopeLocked(ScopeRef scope, String name) {
        BusEntry best = GLOBAL_BUSES.get(name);
        if (scope.kind == ScopeKind.DIMENSION) {
            Map<String, BusEntry> dimension = DIMENSION_BUSES.get(scope.dimension());
            if (dimension != null) best = newerBus(best, dimension.get(name));
        } else if (scope.kind == ScopeKind.PLAYER) {
            ResourceKey<Level> dimensionKey = PLAYER_DIMENSIONS.get(scope.playerId());
            if (dimensionKey != null) {
                Map<String, BusEntry> dimension = DIMENSION_BUSES.get(dimensionKey);
                if (dimension != null) best = newerBus(best, dimension.get(name));
            }
            Map<String, BusEntry> personal = PLAYER_BUSES.get(scope.playerId());
            if (personal != null) best = newerBus(best, personal.get(name));
        }
        return best == null ? null : best.state;
    }

    private static boolean validBusParentLocked(
            ScopeRef scope,
            String target,
            String requestedParent
    ) {
        if (target.isEmpty() || requestedParent.isEmpty() || target.equals(requestedParent)) {
            return false;
        }
        if (AudioBusSystem.MASTER.equals(requestedParent)) return true;

        String cursor = requestedParent;
        Set<String> visited = new LinkedHashSet<>();
        for (int depth = 0; depth <= MAX_BUSES_PER_SCOPE; depth++) {
            if (target.equals(cursor) || !visited.add(cursor)) return false;
            SyncedBusState parent = effectiveBusForScopeLocked(scope, cursor);
            if (parent == null || parent.removed()) return false;
            cursor = parent.parent();
            if (AudioBusSystem.MASTER.equals(cursor)) return true;
        }
        return false;
    }

    /** Resolve removed parents and defensively break cycles for late-join state. */
    private static List<SyncedBusState> normalizeBusParents(List<SyncedBusState> states) {
        Map<String, SyncedBusState> byName = new HashMap<>();
        for (SyncedBusState state : states) byName.put(state.name(), state);

        List<SyncedBusState> normalized = new ArrayList<>(states.size());
        for (SyncedBusState state : states) {
            if (state.removed() || AudioBusSystem.MASTER.equals(state.name())) {
                normalized.add(state);
                continue;
            }
            String desiredParent = state.parent();
            String cursor = desiredParent;
            Set<String> visited = new LinkedHashSet<>();
            visited.add(state.name());
            boolean cycle = false;
            boolean resolvingRemovedPrefix = true;
            for (int depth = 0; depth <= MAX_BUSES_PER_SCOPE; depth++) {
                if (AudioBusSystem.MASTER.equals(cursor)) break;
                if (!visited.add(cursor)) {
                    cycle = true;
                    break;
                }
                SyncedBusState parent = byName.get(cursor);
                if (parent == null) break; // May be a client-local parent.
                if (parent.removed() && resolvingRemovedPrefix) {
                    desiredParent = parent.parent();
                } else if (!parent.removed()) {
                    resolvingRemovedPrefix = false;
                }
                cursor = parent.parent();
            }
            if (cycle) desiredParent = AudioBusSystem.MASTER;
            if (desiredParent.equals(state.parent())) {
                normalized.add(state);
            } else {
                normalized.add(new SyncedBusState(
                        state.revision(), state.name(), desiredParent, state.volume(),
                        state.muted(), state.solo(), state.effectsBypassed(), false
                ));
            }
        }
        return normalized;
    }

    private static boolean putSoundLocked(ScopeRef scope, String id, SoundEntry entry) {
        Map<String, SoundEntry> map = soundMapLocked(scope);
        compactSoundTombstonesLocked(scope, map);
        SoundEntry previous = map.get(id);
        boolean addsLiveSound = entry.state != null && (previous == null || previous.state == null);
        if (addsLiveSound && !canAddLiveSoundLocked(scope)) {
            GFBsAuralis.LOGGER.warn(
                    "Auralis scope {} reached the combined per-player {} instance limit; rejected {}",
                    scope, maxSoundsPerPlayer(), id
            );
            return false;
        }
        map.put(id, entry);
        if (addsLiveSound) incrementLiveSoundCountLocked(scope);
        return true;
    }

    private static void compactSoundTombstonesLocked(
            ScopeRef scope,
            Map<String, SoundEntry> map
    ) {
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SoundEntry> entry = iterator.next();
            SoundEntry state = entry.getValue();
            if (state.state == null
                    && !hasPotentiallyMaskedSoundLocked(scope, entry.getKey(), state.revision)) {
                iterator.remove();
            }
        }
    }

    /**
     * Global, dimension, and fixed-player layers share the configured
     * per-player budget. Using the largest dimension and player layer is
     * deliberately conservative, but guarantees every possible layer union is
     * bounded without scanning every player/dimension pair on each mutation.
     */
    private static boolean canAddLiveSoundLocked(ScopeRef scope) {
        int global = globalLiveSoundCount;
        int dimensionMaximum = maximumDimensionLiveSoundCount;
        int playerMaximum = maximumPlayerLiveSoundCount;
        switch (scope.kind) {
            case GLOBAL -> global++;
            case DIMENSION -> dimensionMaximum = Math.max(
                    dimensionMaximum,
                    DIMENSION_LIVE_SOUND_COUNTS.getOrDefault(scope.dimension(), 0) + 1
            );
            case PLAYER -> playerMaximum = Math.max(
                    playerMaximum,
                    PLAYER_LIVE_SOUND_COUNTS.getOrDefault(scope.playerId(), 0) + 1
            );
        }
        return (long) global + dimensionMaximum + playerMaximum <= maxSoundsPerPlayer();
    }

    private static void incrementLiveSoundCountLocked(ScopeRef scope) {
        switch (scope.kind) {
            case GLOBAL -> globalLiveSoundCount++;
            case DIMENSION -> {
                int count = DIMENSION_LIVE_SOUND_COUNTS.merge(scope.dimension(), 1, Integer::sum);
                maximumDimensionLiveSoundCount = Math.max(maximumDimensionLiveSoundCount, count);
            }
            case PLAYER -> {
                int count = PLAYER_LIVE_SOUND_COUNTS.merge(scope.playerId(), 1, Integer::sum);
                maximumPlayerLiveSoundCount = Math.max(maximumPlayerLiveSoundCount, count);
            }
        }
    }

    private static void decrementLiveSoundCountLocked(ScopeRef scope) {
        switch (scope.kind) {
            case GLOBAL -> globalLiveSoundCount = Math.max(0, globalLiveSoundCount - 1);
            case DIMENSION -> {
                ResourceKey<Level> dimension = scope.dimension();
                int previous = DIMENSION_LIVE_SOUND_COUNTS.getOrDefault(dimension, 0);
                int next = Math.max(0, previous - 1);
                if (next == 0) DIMENSION_LIVE_SOUND_COUNTS.remove(dimension);
                else DIMENSION_LIVE_SOUND_COUNTS.put(dimension, next);
                if (previous == maximumDimensionLiveSoundCount) {
                    maximumDimensionLiveSoundCount = maximumCount(DIMENSION_LIVE_SOUND_COUNTS);
                }
            }
            case PLAYER -> {
                UUID playerId = scope.playerId();
                int previous = PLAYER_LIVE_SOUND_COUNTS.getOrDefault(playerId, 0);
                int next = Math.max(0, previous - 1);
                if (next == 0) PLAYER_LIVE_SOUND_COUNTS.remove(playerId);
                else PLAYER_LIVE_SOUND_COUNTS.put(playerId, next);
                if (previous == maximumPlayerLiveSoundCount) {
                    maximumPlayerLiveSoundCount = maximumCount(PLAYER_LIVE_SOUND_COUNTS);
                }
            }
        }
    }

    private static int maximumCount(Map<?, Integer> counts) {
        int maximum = 0;
        for (int count : counts.values()) maximum = Math.max(maximum, count);
        return maximum;
    }

    private static boolean putBusLocked(ScopeRef scope, String name, BusEntry entry) {
        Map<String, BusEntry> map = busMapLocked(scope);
        compactBusTombstonesLocked(scope, map);
        BusEntry previous = map.get(name);
        boolean addsLiveBus = !entry.state.removed()
                && (previous == null || previous.state.removed());
        if (addsLiveBus && liveBusCount(map) >= MAX_BUSES_PER_SCOPE) {
            GFBsAuralis.LOGGER.warn(
                    "Auralis scope {} reached its {} bus limit; rejected {}",
                    scope, MAX_BUSES_PER_SCOPE, name
            );
            return false;
        }
        if (previous == null && map.size() >= MAX_BUS_STATE_ENTRIES_PER_SCOPE) {
            GFBsAuralis.LOGGER.warn(
                    "Auralis scope {} reached its {} bus-state limit; rejected {}",
                    scope, MAX_BUS_STATE_ENTRIES_PER_SCOPE, name
            );
            return false;
        }
        map.put(name, entry);
        return true;
    }

    private static int liveBusCount(Map<String, BusEntry> map) {
        int count = 0;
        for (BusEntry entry : map.values()) {
            if (!entry.state.removed()) count++;
        }
        return count;
    }

    private static void compactBusTombstonesLocked(ScopeRef scope, Map<String, BusEntry> map) {
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BusEntry> entry = iterator.next();
            SyncedBusState state = entry.getValue().state;
            if (state.removed()
                    && !hasPotentiallyMaskedBusLocked(scope, entry.getKey(), state.revision())) {
                iterator.remove();
            }
        }
    }

    private static boolean hasPotentiallyMaskedBusLocked(
            ScopeRef scope,
            String name,
            long maximumRevision
    ) {
        if (hasLiveBusAtOrBefore(GLOBAL_BUSES.get(name), maximumRevision)) return true;

        if (scope.kind == ScopeKind.GLOBAL || scope.kind == ScopeKind.PLAYER) {
            for (Map<String, BusEntry> dimension : DIMENSION_BUSES.values()) {
                if (hasLiveBusAtOrBefore(dimension.get(name), maximumRevision)) return true;
            }
        } else {
            Map<String, BusEntry> dimension = DIMENSION_BUSES.get(scope.dimension());
            if (dimension != null && hasLiveBusAtOrBefore(dimension.get(name), maximumRevision)) {
                return true;
            }
        }

        if (scope.kind == ScopeKind.GLOBAL || scope.kind == ScopeKind.DIMENSION) {
            for (Map<String, BusEntry> personal : PLAYER_BUSES.values()) {
                if (hasLiveBusAtOrBefore(personal.get(name), maximumRevision)) return true;
            }
        } else {
            Map<String, BusEntry> personal = PLAYER_BUSES.get(scope.playerId());
            if (personal != null && hasLiveBusAtOrBefore(personal.get(name), maximumRevision)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLiveBusAtOrBefore(@Nullable BusEntry entry, long revision) {
        return entry != null && !entry.state.removed() && entry.state.revision() <= revision;
    }

    private static Map<String, SoundEntry> soundMapLocked(ScopeRef scope) {
        return switch (scope.kind) {
            case GLOBAL -> GLOBAL_SOUNDS;
            case DIMENSION -> DIMENSION_SOUNDS.computeIfAbsent(scope.dimension(), ignored -> new HashMap<>());
            case PLAYER -> PLAYER_SOUNDS.computeIfAbsent(scope.playerId(), ignored -> new HashMap<>());
        };
    }

    private static Map<String, BusEntry> busMapLocked(ScopeRef scope) {
        return switch (scope.kind) {
            case GLOBAL -> GLOBAL_BUSES;
            case DIMENSION -> DIMENSION_BUSES.computeIfAbsent(scope.dimension(), ignored -> new HashMap<>());
            case PLAYER -> PLAYER_BUSES.computeIfAbsent(scope.playerId(), ignored -> new HashMap<>());
        };
    }

    private static List<ScopeRef> scopes(AuralisAudience audience) {
        if (audience instanceof AuralisAudience.All) return List.of(ScopeRef.global());
        if (audience instanceof AuralisAudience.Dimension dimension) {
            return List.of(ScopeRef.dimension(dimension.dimension()));
        }
        if (audience instanceof AuralisAudience.Players players) {
            return players.playerIds().stream().map(ScopeRef::player).toList();
        }
        throw new IllegalArgumentException("Unknown Auralis audience: " + audience);
    }

    private static List<ServerPlayer> recipients(AuralisAudience audience) {
        MinecraftServer server = server();
        return server == null ? List.of() : audience.resolve(server);
    }

    private static void scheduleExpiryLocked(ScopeRef scope, SyncedSoundState state, long now) {
        ScopedSoundKey key = new ScopedSoundKey(scope, state.id());
        cancelExpiryLocked(key);
        if (state.looping()
                || state.playbackStatus() != SyncedSoundState.PlaybackStatus.PLAYING
                || state.durationSeconds() <= 0.0) {
            return;
        }
        ScheduledThreadPoolExecutor executor = runtimeExecutor;
        if (executor == null || executor.isShutdown()) return;
        double remaining = Math.max(0.0, state.durationSeconds() - state.playbackPositionAt(now));
        double rate = Math.max(0.01, Math.min(8.0, state.pitchAt(now) * state.speedAt(now)));
        long delayNanos = Math.max(1_000_000L, (long) (remaining / rate * 1_000_000_000.0));
        if (state.hasActiveRateTween(now)) delayNanos = Math.min(delayNanos, 500_000_000L);
        ScheduledFuture<?> future = executor.schedule(
                () -> expireIfNeeded(key, state.revision()), delayNanos, TimeUnit.NANOSECONDS
        );
        EXPIRY_TASKS.put(key, future);
    }

    private static void expireIfNeeded(ScopedSoundKey key, long expectedRevision) {
        AuralisAudience audience = key.scope.audience();
        boolean expired = false;
        long removalRevision = -1L;
        long now = System.nanoTime();
        STATE_LOCK.writeLock().lock();
        try {
            Map<String, SoundEntry> map = soundMapLocked(key.scope);
            SoundEntry entry = map.get(key.id);
            if (entry == null || entry.revision != expectedRevision || entry.state == null) return;
            SoundEntry effective = effectiveSoundEntryForScopeLocked(key.scope, key.id);
            if (effective == null || effective.revision != expectedRevision) {
                // A newer broader/narrower scope replaced this logical generation.
                // Letting the stale expiry create a fresh tombstone would wrongly
                // stop that newer sound for this audience.
                if (map.remove(key.id, entry)) decrementLiveSoundCountLocked(key.scope);
                EXPIRY_TASKS.remove(key);
                return;
            }
            SyncedSoundState materialized = entry.state.materialize(now);
            if (materialized.isKnownComplete(now)) {
                if (map.remove(key.id, entry)) decrementLiveSoundCountLocked(key.scope);
                // Keep the expired generation's revision only when it must mask
                // an older overlapping scope. A newer narrower sound still wins,
                // while the separate removal revision advances connected clients.
                if (hasPotentiallyMaskedSoundLocked(key.scope, key.id, expectedRevision)) {
                    map.put(key.id, SoundEntry.tombstone(expectedRevision));
                }
                removalRevision = nextRevision();
                EXPIRY_TASKS.remove(key);
                expired = true;
            } else {
                map.put(key.id, SoundEntry.state(materialized));
                scheduleExpiryLocked(key.scope, materialized, now);
            }
        } finally {
            STATE_LOCK.writeLock().unlock();
        }
        if (expired) notifySoundChangeAsync(audience, key.id, removalRevision);
    }

    private static void notifySoundChangeAsync(
            AuralisAudience audience,
            String id,
            long minimumRemovalRevision
    ) {
        MinecraftServer server = server();
        if (server == null) return;
        try {
            server.execute(() -> notifySoundChange(audience, id, minimumRemovalRevision));
        } catch (RuntimeException rejected) {
            GFBsAuralis.LOGGER.debug("Auralis expiry delta rejected during shutdown");
        }
    }

    private static boolean hasPotentiallyMaskedSoundLocked(
            ScopeRef scope,
            String id,
            long maximumRevision
    ) {
        if (hasLiveSoundAtOrBefore(GLOBAL_SOUNDS.get(id), maximumRevision)) return true;

        if (scope.kind == ScopeKind.GLOBAL || scope.kind == ScopeKind.PLAYER) {
            for (Map<String, SoundEntry> dimension : DIMENSION_SOUNDS.values()) {
                if (hasLiveSoundAtOrBefore(dimension.get(id), maximumRevision)) return true;
            }
        } else {
            Map<String, SoundEntry> dimension = DIMENSION_SOUNDS.get(scope.dimension());
            if (dimension != null && hasLiveSoundAtOrBefore(dimension.get(id), maximumRevision)) {
                return true;
            }
        }

        if (scope.kind == ScopeKind.GLOBAL || scope.kind == ScopeKind.DIMENSION) {
            for (Map<String, SoundEntry> personal : PLAYER_SOUNDS.values()) {
                if (hasLiveSoundAtOrBefore(personal.get(id), maximumRevision)) return true;
            }
        } else {
            Map<String, SoundEntry> personal = PLAYER_SOUNDS.get(scope.playerId());
            if (personal != null && hasLiveSoundAtOrBefore(personal.get(id), maximumRevision)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLiveSoundAtOrBefore(@Nullable SoundEntry entry, long revision) {
        return entry != null && entry.state != null && entry.revision <= revision;
    }

    private static void cancelExpiryLocked(ScopedSoundKey key) {
        ScheduledFuture<?> previous = EXPIRY_TASKS.remove(key);
        if (previous != null) previous.cancel(false);
    }

    private static void ensureRuntimeLocked() {
        if (currentServer == null) currentServer = ServerLifecycleHooks.getCurrentServer();
        ensureExecutorLocked();
    }

    private static void ensureExecutorLocked() {
        if (runtimeExecutor != null && !runtimeExecutor.isShutdown()) return;
        AtomicInteger index = new AtomicInteger(0);
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "Auralis-ServerRuntime-" + index.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((worker, failure) ->
                    GFBsAuralis.LOGGER.error("Uncaught failure on {}", worker.getName(), failure));
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        runtimeExecutor = executor;
    }

    private static void clearStateLocked() {
        for (ScheduledFuture<?> future : EXPIRY_TASKS.values()) future.cancel(false);
        EXPIRY_TASKS.clear();
        GLOBAL_SOUNDS.clear();
        DIMENSION_SOUNDS.clear();
        PLAYER_SOUNDS.clear();
        DIMENSION_LIVE_SOUND_COUNTS.clear();
        PLAYER_LIVE_SOUND_COUNTS.clear();
        globalLiveSoundCount = 0;
        maximumDimensionLiveSoundCount = 0;
        maximumPlayerLiveSoundCount = 0;
        GLOBAL_BUSES.clear();
        DIMENSION_BUSES.clear();
        PLAYER_BUSES.clear();
        PLAYER_DIMENSIONS.clear();
        PENDING_SNAPSHOTS.clear();
        LAST_CLIENT_SNAPSHOT_REQUEST.clear();
    }

    private static MinecraftServer server() {
        MinecraftServer server = currentServer;
        return server != null ? server : ServerLifecycleHooks.getCurrentServer();
    }

    private static long nextRevision() {
        return NEXT_REVISION.incrementAndGet();
    }

    private static int maxSoundsPerPlayer() {
        return GFBsAuralisConfig.SERVER.maxConcurrentSounds.get();
    }

    private static String legacyId(ResourceLocation soundId) {
        return "legacy/" + soundId;
    }

    private static SoundEntry newerSound(@Nullable SoundEntry left, @Nullable SoundEntry right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.revision > left.revision ? right : left;
    }

    private static BusEntry newerBus(@Nullable BusEntry left, @Nullable BusEntry right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.state.revision() > left.state.revision() ? right : left;
    }

    private static void send(ServerPlayer player, Object packet) {
        if (player == null || player.hasDisconnected()) return;
        NetworkHandler.CHANNEL.sendTo(
                packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT
        );
    }

    private record SoundEntry(long revision, @Nullable SyncedSoundState state) {
        private static SoundEntry state(SyncedSoundState state) {
            return new SoundEntry(state.revision(), state);
        }
        private static SoundEntry tombstone(long revision) { return new SoundEntry(revision, null); }
    }

    private record BusEntry(SyncedBusState state) {
    }

    private enum ScopeKind { GLOBAL, DIMENSION, PLAYER }

    private record ScopeRef(ScopeKind kind, @Nullable Object key) {
        private static ScopeRef global() { return new ScopeRef(ScopeKind.GLOBAL, null); }
        private static ScopeRef dimension(ResourceKey<Level> dimension) {
            return new ScopeRef(ScopeKind.DIMENSION, dimension);
        }
        private static ScopeRef player(UUID playerId) { return new ScopeRef(ScopeKind.PLAYER, playerId); }

        @SuppressWarnings("unchecked")
        private ResourceKey<Level> dimension() { return (ResourceKey<Level>) key; }
        private UUID playerId() { return (UUID) key; }
        private AuralisAudience audience() {
            return switch (kind) {
                case GLOBAL -> AuralisAudience.all();
                case DIMENSION -> AuralisAudience.dimension(dimension());
                case PLAYER -> new AuralisAudience.Players(Set.of(playerId()));
            };
        }
    }

    private record ScopedSoundKey(ScopeRef scope, String id) {
    }

    private record SnapshotEntry(@Nullable SyncedBusState bus, @Nullable SyncedSoundState sound) {
    }

    private record SnapshotTarget(UUID playerId, ResourceKey<Level> dimension) {
    }
}
