package org.lytharalab.gfbs.auralis.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.GFBsAuralisConfig;
import org.lytharalab.gfbs.auralis.api.*;
import org.lytharalab.gfbs.auralis.network.AuthoritativeSnapshotPacket;
import org.lytharalab.gfbs.auralis.network.AuthoritativeSoundStatePacket;
import org.lytharalab.gfbs.auralis.network.ClientSoundAckPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** Server-owned state machine. It deliberately has no client/OpenAL dependencies. */
public final class AuralisServerManager {
    private static final int SNAPSHOT_CHUNK_SIZE = 96;
    private static final Map<MinecraftServer, Authority> AUTHORITIES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private AuralisServerManager() { }

    public static AuralisOperation<ServerSoundInstance> create(MinecraftServer server, String rawId,
                                                                AuralisSoundSpec spec,
                                                                Collection<ServerPlayer> audience) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(spec, "spec");
        String id = validateId(rawId);
        Set<UUID> requestedAudience = audienceIds(audience);
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ServerSoundInstance> result = new CompletableFuture<>();
        submit(server, result, () -> {
            if (!GFBsAuralisConfig.SERVER.enableRemoteSounds.get()) {
                throw new IllegalStateException("Server-authoritative Auralis sounds are disabled");
            }
            Authority authority = authority(server);
            authority.checkCapacity(requestedAudience, id);
            MutableSound previous = authority.sounds.get(id);
            if (previous != null) {
                previous.state = AuralisPlaybackState.DISPOSED;
                previous.revision = increment(previous.revision, "revision");
                authority.broadcastUntracked(previous.snapshot(), previous.recipients());
            }
            long epoch = authority.nextEpoch();
            MutableSound sound = new MutableSound(server, id, epoch, spec, requestedAudience, serviceTick(server));
            authority.sounds.put(id, sound);
            ServerSoundInstance handle = new ServerSoundInstance(server, id, epoch);
            authority.broadcast(operationId, sound.snapshot(), sound.recipients())
                    .whenComplete((operation, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else if (!operation.allApplied()) result.completeExceptionally(
                                new IllegalStateException("One or more clients failed to create authoritative sound " + id));
                        else result.complete(handle);
                    });
        });
        return AuralisOperation.withId(operationId, AuralisOperation.Kind.CREATE, result);
    }

    public static Optional<ServerSoundInstance> find(MinecraftServer server, String rawId) {
        String id = validateId(rawId);
        Authority authority = AUTHORITIES.get(server);
        if (authority == null) return Optional.empty();
        MutableSound sound = authority.sounds.get(id);
        return sound == null ? Optional.empty() : Optional.of(new ServerSoundInstance(server, id, sound.epoch));
    }

    public static Optional<AuthoritativeSoundSnapshot> snapshot(ServerSoundInstance handle) {
        Authority authority = AUTHORITIES.get(handle.server());
        if (authority == null) return Optional.empty();
        MutableSound sound = authority.sounds.get(handle.id());
        return sound == null || sound.epoch != handle.epoch() ? Optional.empty() : Optional.of(sound.snapshot());
    }

    public static AuralisOperation<ServerOperationResult> play(ServerSoundInstance handle) {
        return mutate(handle, AuralisOperation.Kind.PLAY, sound -> {
            double cursor = sound.currentPosition();
            if (!sound.spec.looping() && sound.durationSeconds > 0.0 && cursor >= sound.durationSeconds) cursor = 0.0;
            sound.anchorPositionSeconds = cursor;
            sound.anchorServerTick = serviceTick(sound.server);
            sound.state = AuralisPlaybackState.PLAYING;
        });
    }

    public static AuralisOperation<ServerOperationResult> pause(ServerSoundInstance handle) {
        return mutate(handle, AuralisOperation.Kind.PAUSE, sound -> {
            sound.anchorPositionSeconds = sound.currentPosition();
            sound.anchorServerTick = serviceTick(sound.server);
            sound.state = AuralisPlaybackState.PAUSED;
        });
    }

    public static AuralisOperation<ServerOperationResult> stop(ServerSoundInstance handle) {
        return mutate(handle, AuralisOperation.Kind.STOP, sound -> {
            sound.anchorPositionSeconds = 0.0;
            sound.anchorServerTick = serviceTick(sound.server);
            sound.state = AuralisPlaybackState.STOPPED;
        });
    }

    public static AuralisOperation<ServerOperationResult> seek(ServerSoundInstance handle, double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            return AuralisOperation.failed(AuralisOperation.Kind.SEEK, new IllegalArgumentException("Invalid playback position"));
        }
        return mutate(handle, AuralisOperation.Kind.SEEK, sound -> {
            double cursor = sound.durationSeconds > 0.0
                    ? (sound.spec.looping() ? seconds % sound.durationSeconds : Math.min(seconds, sound.durationSeconds))
                    : seconds;
            sound.anchorPositionSeconds = cursor;
            sound.anchorServerTick = serviceTick(sound.server);
        });
    }

    public static AuralisOperation<ServerOperationResult> update(ServerSoundInstance handle, AuralisSoundSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return mutate(handle, AuralisOperation.Kind.UPDATE, sound -> {
            sound.anchorPositionSeconds = sound.currentPosition();
            sound.anchorServerTick = serviceTick(sound.server);
            if (!sound.spec.soundEventId().equals(spec.soundEventId()) || sound.spec.streamed() != spec.streamed()) {
                sound.durationSeconds = 0.0;
            }
            sound.spec = spec;
        });
    }

    public static AuralisOperation<ServerOperationResult> setGlobal(ServerSoundInstance handle, boolean global) {
        return mutate(handle, AuralisOperation.Kind.SET_GLOBAL, sound -> sound.global = global);
    }

    public static AuralisOperation<ServerOperationResult> clearGlobal(ServerSoundInstance handle,
                                                                      Collection<ServerPlayer> audience) {
        Set<UUID> nextAudience = audienceIds(audience);
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ServerOperationResult> result = new CompletableFuture<>();
        submit(handle.server(), result, () -> {
            Authority authority = authority(handle.server());
            MutableSound sound = authority.require(handle);
            Set<UUID> oldRecipients = sound.recipients();
            sound.global = false;
            sound.audience = nextAudience;
            sound.revision = increment(sound.revision, "revision");
            AuthoritativeSoundSnapshot snapshot = sound.snapshot();
            Set<UUID> nextRecipients = sound.recipients();
            Set<UUID> all = new HashSet<>(oldRecipients);
            all.addAll(nextRecipients);
            authority.broadcastMixed(operationId, snapshot, all, nextRecipients)
                    .whenComplete((value, failure) -> complete(result, value, failure));
        });
        return AuralisOperation.withId(operationId, AuralisOperation.Kind.SET_GLOBAL, result);
    }

    public static AuralisOperation<ServerOperationResult> dispose(ServerSoundInstance handle) {
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ServerOperationResult> result = new CompletableFuture<>();
        submit(handle.server(), result, () -> {
            Authority authority = authority(handle.server());
            MutableSound sound = authority.require(handle);
            sound.anchorPositionSeconds = sound.currentPosition();
            sound.anchorServerTick = serviceTick(sound.server);
            sound.state = AuralisPlaybackState.DISPOSED;
            sound.revision = increment(sound.revision, "revision");
            AuthoritativeSoundSnapshot snapshot = sound.snapshot();
            Set<UUID> recipients = sound.recipients();
            authority.sounds.remove(sound.id, sound);
            authority.broadcast(operationId, snapshot, recipients)
                    .whenComplete((value, failure) -> complete(result, value, failure));
        });
        return AuralisOperation.withId(operationId, AuralisOperation.Kind.DISPOSE, result);
    }

    public static void acknowledge(ServerPlayer player, ClientSoundAckPacket ack) {
        Authority authority = AUTHORITIES.get(player.server);
        if (authority == null) return;
        authority.acknowledge(player.getUUID(), ack);
    }

    public static void tick(MinecraftServer server) {
        Authority authority = AUTHORITIES.get(server);
        if (authority != null) authority.tick();
    }

    /** Monotonic 20 Hz service timeline used by packets and state anchors. */
    public static long serviceTick(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    public static void onPlayerLogin(ServerPlayer player) {
        authority(player.server).sendAtomicSnapshot(player, true);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        Authority authority = AUTHORITIES.get(player.server);
        if (authority != null) authority.disconnect(player.getUUID());
    }

    public static void syncAllSoundsToPlayer(ServerPlayer player) {
        authority(player.server).sendAtomicSnapshot(player, true);
    }

    public static void onServerStop(MinecraftServer server) {
        Authority authority = AUTHORITIES.remove(server);
        if (authority != null) authority.close();
    }

    private static AuralisOperation<ServerOperationResult> mutate(ServerSoundInstance handle,
                                                                  AuralisOperation.Kind kind,
                                                                  Mutation mutation) {
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ServerOperationResult> result = new CompletableFuture<>();
        submit(handle.server(), result, () -> {
            Authority authority = authority(handle.server());
            MutableSound sound = authority.require(handle);
            mutation.apply(sound);
            sound.revision = increment(sound.revision, "revision");
            authority.broadcast(operationId, sound.snapshot(), sound.recipients())
                    .whenComplete((value, failure) -> complete(result, value, failure));
        });
        return AuralisOperation.withId(operationId, kind, result);
    }

    private static void complete(CompletableFuture<ServerOperationResult> future,
                                 ServerOperationResult value, Throwable failure) {
        if (failure != null) future.completeExceptionally(failure); else future.complete(value);
    }

    private static void submit(MinecraftServer server, CompletableFuture<?> result, Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        try {
            if (server.isSameThread()) guarded.run(); else server.execute(guarded);
        } catch (Throwable failure) {
            result.completeExceptionally(failure instanceof RejectedExecutionException ? failure
                    : new IllegalStateException("Unable to schedule Auralis server operation", failure));
        }
    }

    private static Authority authority(MinecraftServer server) {
        return AUTHORITIES.computeIfAbsent(server, Authority::new);
    }

    private static Set<UUID> audienceIds(Collection<ServerPlayer> players) {
        if (players == null || players.isEmpty()) return Set.of();
        Set<UUID> ids = new HashSet<>();
        for (ServerPlayer player : players) if (player != null) ids.add(player.getUUID());
        return Set.copyOf(ids);
    }

    private static String validateId(String raw) {
        String id = Objects.requireNonNull(raw, "id").trim();
        if (id.isEmpty() || id.length() > 128 || !id.matches("[a-zA-Z0-9_.:/-]+")) {
            throw new IllegalArgumentException("Invalid Auralis sound id: " + raw);
        }
        return id;
    }

    private static long increment(long value, String name) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException("Auralis " + name + " exhausted");
        return value + 1L;
    }

    @FunctionalInterface
    private interface Mutation { void apply(MutableSound sound); }

    private static final class MutableSound {
        final MinecraftServer server;
        final String id;
        final long epoch;
        volatile AuralisSoundSpec spec;
        volatile Set<UUID> audience;
        volatile boolean global;
        volatile long revision = 1L;
        volatile AuralisPlaybackState state = AuralisPlaybackState.CREATED;
        volatile long anchorServerTick;
        volatile double anchorPositionSeconds;
        volatile double durationSeconds;

        MutableSound(MinecraftServer server, String id, long epoch, AuralisSoundSpec spec,
                     Set<UUID> audience, long serverTick) {
            this.server = server;
            this.id = id;
            this.epoch = epoch;
            this.spec = spec;
            this.audience = audience;
            this.anchorServerTick = serverTick;
        }

        double currentPosition() {
            return AuthoritativeTimelineMath.positionAt(state, anchorPositionSeconds, anchorServerTick,
                    serviceTick(server), spec.pitch() * spec.speed(), spec.looping(), durationSeconds);
        }

        AuthoritativeSoundSnapshot snapshot() {
            return new AuthoritativeSoundSnapshot(id, epoch, revision, spec, state, anchorServerTick,
                    anchorPositionSeconds, durationSeconds, global);
        }

        AuthoritativeSoundSnapshot transmissionSnapshot() {
            if (state != AuralisPlaybackState.PLAYING) return snapshot();
            long nowTick = serviceTick(server);
            return new AuthoritativeSoundSnapshot(id, epoch, revision, spec, state, nowTick,
                    currentPosition(), durationSeconds, global);
        }

        Set<UUID> recipients() {
            if (!global) return audience;
            Set<UUID> ids = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) ids.add(player.getUUID());
            return ids;
        }
    }

    private static final class Authority {
        final MinecraftServer server;
        final Map<String, MutableSound> sounds = new ConcurrentHashMap<>();
        final Map<UUID, PendingOperation> pending = new HashMap<>();
        long epochCounter;
        long lastSnapshotTick = Long.MIN_VALUE;

        Authority(MinecraftServer server) { this.server = server; }

        long nextEpoch() { epochCounter = increment(epochCounter, "epoch"); return epochCounter; }

        MutableSound require(ServerSoundInstance handle) {
            MutableSound sound = sounds.get(handle.id());
            if (sound == null || sound.epoch != handle.epoch()) throw new IllegalStateException("Stale or disposed server sound handle: " + handle.id());
            return sound;
        }

        void checkCapacity(Set<UUID> audience, String replacingId) {
            int limit = GFBsAuralisConfig.SERVER.maxConcurrentSounds.get();
            for (UUID playerId : audience) {
                int count = 0;
                for (MutableSound sound : sounds.values()) {
                    if (!sound.id.equals(replacingId) && (sound.global || sound.audience.contains(playerId))) count++;
                }
                if (count >= limit) throw new IllegalStateException("Auralis sound limit reached for player " + playerId);
            }
        }

        CompletableFuture<ServerOperationResult> broadcast(UUID operationId, AuthoritativeSoundSnapshot snapshot,
                                                           Set<UUID> recipients) {
            return broadcastMixed(operationId, snapshot, recipients, recipients);
        }

        CompletableFuture<ServerOperationResult> broadcastMixed(UUID operationId, AuthoritativeSoundSnapshot snapshot,
                                                                Set<UUID> recipients, Set<UUID> retain) {
            Map<UUID, ClientExecutionResult> immediate = new HashMap<>();
            Set<UUID> waiting = new HashSet<>();
            for (UUID playerId : recipients) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    immediate.put(playerId, new ClientExecutionResult(playerId, ClientExecutionStatus.DISCONNECTED, "Player is offline", 0.0));
                    continue;
                }
                AuthoritativeSoundSnapshot outgoing = retain.contains(playerId) ? snapshot
                        : new AuthoritativeSoundSnapshot(snapshot.id(), snapshot.epoch(), snapshot.revision(), snapshot.spec(),
                        AuralisPlaybackState.DISPOSED, snapshot.anchorServerTick(), snapshot.anchorPositionSeconds(),
                        snapshot.durationSeconds(), false);
                try {
                    NetworkHandler.CHANNEL.sendTo(new AuthoritativeSoundStatePacket(operationId, outgoing),
                            player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                    waiting.add(playerId);
                } catch (Throwable failure) {
                    immediate.put(playerId, new ClientExecutionResult(playerId, ClientExecutionStatus.REJECTED,
                            String.valueOf(failure.getMessage()), 0.0));
                }
            }
            PendingOperation operation = new PendingOperation(operationId, snapshot, waiting, immediate,
                    serviceTick(server) + GFBsAuralisConfig.SERVER.acknowledgementTimeoutTicks.get());
            if (waiting.isEmpty()) operation.complete(); else pending.put(operationId, operation);
            return operation.future;
        }

        void broadcastUntracked(AuthoritativeSoundSnapshot snapshot, Set<UUID> recipients) {
            UUID operationId = UUID.randomUUID();
            broadcast(operationId, snapshot, recipients);
        }

        void acknowledge(UUID playerId, ClientSoundAckPacket ack) {
            PendingOperation operation = pending.get(ack.operationId());
            if (operation == null || !operation.waiting.contains(playerId)) return;
            AuthoritativeSoundSnapshot snapshot = operation.snapshot;
            if (!snapshot.id().equals(ack.soundId()) || snapshot.epoch() != ack.epoch() || snapshot.revision() != ack.revision()) return;
            operation.waiting.remove(playerId);
            operation.results.put(playerId, new ClientExecutionResult(playerId, ack.status(), ack.detail(), ack.durationSeconds()));

            MutableSound sound = sounds.get(snapshot.id());
            if (sound != null && sound.epoch == ack.epoch() && sound.durationSeconds <= 0.0
                    && (ack.status() == ClientExecutionStatus.APPLIED || ack.status() == ClientExecutionStatus.STALE)
                    && Double.isFinite(ack.durationSeconds()) && ack.durationSeconds() > 0.0 && ack.durationSeconds() <= 86_400.0) {
                sound.durationSeconds = ack.durationSeconds();
            }
            if (operation.waiting.isEmpty()) {
                pending.remove(operation.operationId);
                operation.complete();
            }
        }

        void tick() {
            long tick = serviceTick(server);
            List<MutableSound> naturalStops = new ArrayList<>();
            for (MutableSound sound : sounds.values()) {
                if (sound.state == AuralisPlaybackState.PLAYING && !sound.spec.looping() && sound.durationSeconds > 0.0
                        && sound.currentPosition() >= sound.durationSeconds) naturalStops.add(sound);
            }
            for (MutableSound sound : naturalStops) {
                sound.anchorPositionSeconds = sound.durationSeconds;
                sound.anchorServerTick = tick;
                sound.state = AuralisPlaybackState.STOPPED;
                sound.revision = increment(sound.revision, "revision");
                broadcastUntracked(sound.snapshot(), sound.recipients());
            }

            List<PendingOperation> timedOut = new ArrayList<>();
            for (PendingOperation operation : pending.values()) if (tick >= operation.deadlineTick) timedOut.add(operation);
            for (PendingOperation operation : timedOut) {
                pending.remove(operation.operationId);
                for (UUID playerId : operation.waiting) operation.results.put(playerId,
                        new ClientExecutionResult(playerId, ClientExecutionStatus.TIMED_OUT, "Client ACK timed out", 0.0));
                operation.waiting.clear();
                operation.complete();
            }

            int interval = GFBsAuralisConfig.SERVER.timelineSyncIntervalTicks.get();
            if (lastSnapshotTick == Long.MIN_VALUE || tick - lastSnapshotTick >= interval) {
                lastSnapshotTick = tick;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) sendAtomicSnapshot(player, true);
            }
        }

        void disconnect(UUID playerId) {
            for (PendingOperation operation : new ArrayList<>(pending.values())) {
                if (!operation.waiting.remove(playerId)) continue;
                operation.results.put(playerId, new ClientExecutionResult(playerId, ClientExecutionStatus.DISCONNECTED,
                        "Player disconnected", 0.0));
                if (operation.waiting.isEmpty()) {
                    pending.remove(operation.operationId);
                    operation.complete();
                }
            }
        }

        void sendAtomicSnapshot(ServerPlayer player, boolean full) {
            List<AuthoritativeSoundSnapshot> visible = new ArrayList<>();
            UUID playerId = player.getUUID();
            for (MutableSound sound : sounds.values()) {
                if (sound.global || sound.audience.contains(playerId)) visible.add(sound.transmissionSnapshot());
            }
            visible.sort(Comparator.comparing(AuthoritativeSoundSnapshot::id));
            int chunkCount = Math.max(1, (visible.size() + SNAPSHOT_CHUNK_SIZE - 1) / SNAPSHOT_CHUNK_SIZE);
            UUID snapshotId = UUID.randomUUID();
            for (int index = 0; index < chunkCount; index++) {
                int from = index * SNAPSHOT_CHUNK_SIZE;
                int to = Math.min(visible.size(), from + SNAPSHOT_CHUNK_SIZE);
                List<AuthoritativeSoundSnapshot> chunk = from >= to ? List.of() : visible.subList(from, to);
                try {
                    NetworkHandler.CHANNEL.sendTo(new AuthoritativeSnapshotPacket(snapshotId, index, chunkCount, full, chunk),
                            player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                } catch (Throwable failure) {
                    GFBsAuralis.LOGGER.debug("Unable to send authoritative Auralis snapshot to {}: {}", playerId, failure.getMessage());
                    return;
                }
            }
        }

        void close() {
            for (PendingOperation operation : pending.values()) {
                for (UUID playerId : operation.waiting) operation.results.put(playerId,
                        new ClientExecutionResult(playerId, ClientExecutionStatus.DISCONNECTED, "Server stopped", 0.0));
                operation.waiting.clear();
                operation.complete();
            }
            pending.clear();
            sounds.clear();
        }
    }

    private static final class PendingOperation {
        final UUID operationId;
        final AuthoritativeSoundSnapshot snapshot;
        final Set<UUID> waiting;
        final Map<UUID, ClientExecutionResult> results;
        final long deadlineTick;
        final CompletableFuture<ServerOperationResult> future = new CompletableFuture<>();

        PendingOperation(UUID operationId, AuthoritativeSoundSnapshot snapshot, Set<UUID> waiting,
                         Map<UUID, ClientExecutionResult> immediate, long deadlineTick) {
            this.operationId = operationId;
            this.snapshot = snapshot;
            this.waiting = waiting;
            this.results = immediate;
            this.deadlineTick = deadlineTick;
        }

        void complete() { future.complete(new ServerOperationResult(operationId, snapshot, results)); }
    }
}
