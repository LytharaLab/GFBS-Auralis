package org.lytharalab.gfbs.auralis;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.auralis.api.*;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.network.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Client mirrors of server-owned voices. All entry points run on the Minecraft client thread. */
@OnlyIn(Dist.CLIENT)
public final class ClientAuthorityController {
    private static final int CLOCK_BOOTSTRAP_PROBES = 3;
    private static final long CLOCK_BOOTSTRAP_INTERVAL_TICKS = 10L;
    private static final long CLOCK_PROBE_RETRY_TICKS = 20L;
    private static final Map<String, Mirror> MIRRORS = new HashMap<>();
    private static final Map<UUID, SnapshotAssembly> ASSEMBLIES = new HashMap<>();
    private static final ServerTickEstimator SERVER_CLOCK = new ServerTickEstimator();
    private static long clientTick;
    private static long probeNonce;
    private static long nextClockProbeTick;
    private static int clockBootstrapProbesRemaining = CLOCK_BOOTSTRAP_PROBES;

    private ClientAuthorityController() { }

    public static void accept(AuthoritativeSoundStatePacket packet) {
        apply(packet.snapshot(), packet.operationId());
    }

    public static void accept(AuthoritativeSnapshotPacket packet) {
        SnapshotAssembly assembly = ASSEMBLIES.computeIfAbsent(packet.snapshotId(), ignored ->
                new SnapshotAssembly(packet.chunkCount(), packet.full(), System.nanoTime()));
        if (assembly.chunkCount != packet.chunkCount() || assembly.full != packet.full()) {
            ASSEMBLIES.remove(packet.snapshotId());
            return;
        }
        assembly.chunks.putIfAbsent(packet.chunkIndex(), packet.sounds());
        if (assembly.chunks.size() != assembly.chunkCount) return;
        ASSEMBLIES.remove(packet.snapshotId());

        List<AuthoritativeSoundSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < assembly.chunkCount; index++) snapshots.addAll(assembly.chunks.getOrDefault(index, List.of()));
        Set<String> present = new HashSet<>();
        for (AuthoritativeSoundSnapshot snapshot : snapshots) {
            present.add(snapshot.id());
            apply(snapshot, null);
        }
        if (assembly.full) {
            for (String id : new ArrayList<>(MIRRORS.keySet())) {
                if (!present.contains(id)) removeMirror(id);
            }
        }
    }

    public static void accept(ServerTimeSyncPacket packet, long receiveNanos) {
        SERVER_CLOCK.accept(packet.clientSendNanos(), receiveNanos, packet.serverTick());
        if (clockBootstrapProbesRemaining > 0) {
            clockBootstrapProbesRemaining--;
            if (clockBootstrapProbesRemaining == 0) scheduleMaintenanceProbe();
        }
    }

    public static void tick() {
        if (Minecraft.getInstance().getConnection() == null) return;
        clientTick++;

        long cutoff = System.nanoTime() - 10_000_000_000L;
        ASSEMBLIES.entrySet().removeIf(entry -> entry.getValue().createdNanos < cutoff);
        if (MIRRORS.isEmpty()) return;

        sendClockProbeIfDue();
        if (!SERVER_CLOCK.isInitialized()) return;

        TimelineCalibrationPolicy policy = calibrationPolicy();
        long now = System.nanoTime();
        for (Mirror mirror : new ArrayList<>(MIRRORS.values())) {
            AuthoritativeSoundSnapshot authority = mirror.authority;
            AuralisSoundInstance instance = mirror.instance;
            if (authority == null || instance == null || authority.state() != AuralisPlaybackState.PLAYING
                    || !mirror.tail.isDone()) continue;
            double expected = authority.expectedPosition(SERVER_CLOCK.estimate(now, authority.anchorServerTick()));
            double duration = instance.getDurationSeconds();
            if (!authority.spec().looping() && duration > 0.0 && expected >= duration) continue;
            if (!instance.isPlaying()) {
                mirror.recoveryQueued = true;
                mirror.tail = mirror.tail.handle((ignored, failure) -> null)
                        .thenCompose(ignored -> instance.seek(expected).future())
                        .thenCompose(ignored -> instance.play().future())
                        .thenAccept(ignored -> mirror.recoveryQueued = false)
                        .exceptionally(failure -> { mirror.recoveryQueued = false; return null; });
                continue;
            }
            if (!mirror.recoveryQueued && instance instanceof AuralisSoundInstanceImpl impl) {
                impl.calibrateAuthoritative(expected, policy);
            }
        }
    }

    public static void reset() {
        for (Mirror mirror : MIRRORS.values()) mirror.dispose();
        MIRRORS.clear();
        ASSEMBLIES.clear();
        SERVER_CLOCK.reset();
        clientTick = 0L;
        probeNonce = 0L;
        nextClockProbeTick = 0L;
        clockBootstrapProbesRemaining = CLOCK_BOOTSTRAP_PROBES;
    }

    private static void apply(AuthoritativeSoundSnapshot snapshot, UUID operationId) {
        Mirror current = MIRRORS.get(snapshot.id());
        if (current != null && (snapshot.epoch() < current.epoch
                || (snapshot.epoch() == current.epoch && snapshot.revision() <= current.revision))) {
            if (operationId != null) acknowledge(operationId, snapshot, ClientExecutionStatus.STALE, "Already applied", current.duration());
            return;
        }
        if (current == null || snapshot.epoch() > current.epoch) {
            if (current != null) current.dispose();
            if (MIRRORS.isEmpty() && snapshot.state() != AuralisPlaybackState.DISPOSED) {
                clockBootstrapProbesRemaining = CLOCK_BOOTSTRAP_PROBES;
                nextClockProbeTick = clientTick;
            }
            current = new Mirror(snapshot.id(), snapshot.epoch());
            MIRRORS.put(snapshot.id(), current);
        }
        final Mirror mirror = current;
        mirror.revision = snapshot.revision();
        mirror.authority = snapshot;
        mirror.tail = mirror.tail.handle((ignored, failure) -> null)
                .thenComposeAsync(ignored -> applySnapshot(mirror, snapshot), Minecraft.getInstance())
                .whenComplete((ignored, failure) -> {
                    if (failure != null && snapshot.state() != AuralisPlaybackState.DISPOSED
                            && mirror.revision == snapshot.revision()) {
                        mirror.revision = Math.max(0L, snapshot.revision() - 1L);
                    }
                    if (operationId == null) return;
                    if (failure == null) acknowledge(operationId, snapshot, ClientExecutionStatus.APPLIED, "", mirror.duration());
                    else acknowledge(operationId, snapshot, ClientExecutionStatus.REJECTED, failureMessage(failure), mirror.duration());
                });
    }

    private static CompletableFuture<Void> applySnapshot(Mirror mirror, AuthoritativeSoundSnapshot snapshot) {
        if (snapshot.state() == AuralisPlaybackState.DISPOSED) {
            MIRRORS.remove(snapshot.id(), mirror);
            AuralisSoundInstance instance = mirror.instance;
            return instance == null ? CompletableFuture.completedFuture(null)
                    : instance.dispose().future().thenAccept(ignored -> {
                        mirror.instance = null;
                        mirror.createdFrom = null;
                    });
        }

        return mirror.ensureInstance(snapshot.spec()).thenComposeAsync(instance -> {
            applySpec(instance, snapshot.spec());
            double expected = snapshot.expectedPosition(SERVER_CLOCK.estimate(System.nanoTime(), snapshot.anchorServerTick()));
            return switch (snapshot.state()) {
                case CREATED -> instance.seek(expected).future().thenAccept(ignored -> { });
                case PLAYING -> {
                    if (instance.isPlaying()) {
                        if (instance instanceof AuralisSoundInstanceImpl impl) {
                            impl.calibrateAuthoritative(expected, calibrationPolicy());
                            yield impl.awaitAudioCommit();
                        }
                        yield CompletableFuture.completedFuture(null);
                    }
                    yield instance.seek(expected).future()
                            .thenCompose(ignored -> instance.play().future())
                            .thenCompose(ignored -> instance instanceof AuralisSoundInstanceImpl impl
                                    ? impl.awaitAudioCommit() : CompletableFuture.completedFuture(null));
                }
                case PAUSED -> instance.seek(expected).future()
                        .thenCompose(ignored -> instance.play().future())
                        .thenCompose(ignored -> instance.pause().future())
                        .thenAccept(ignored -> { });
                case STOPPED -> instance.stop().future()
                        .thenCompose(ignored -> instance.seek(expected).future())
                        .thenAccept(ignored -> { });
                case DISPOSED -> CompletableFuture.completedFuture(null);
            };
        }, Minecraft.getInstance());
    }

    private static void applySpec(AuralisSoundInstance instance, AuralisSoundSpec spec) {
        instance.setVolume(spec.volume()).setPitch(spec.pitch()).setSpeed(spec.speed())
                .setStatic(spec.listenerRelative()).setPosition(spec.position()).setLooping(spec.looping())
                .setPriority(spec.priority()).setMinDistance(spec.minDistance()).setMaxDistance(spec.maxDistance())
                .setAutoDisposeOnFinish(false);
        if (AuralisApi.buses().findBus(spec.bus()).isEmpty()) {
            AuralisApi.buses().createBus(spec.bus(), AudioBusSystem.MASTER);
        }
        instance.setBus(spec.bus());
    }

    private static TimelineCalibrationPolicy calibrationPolicy() {
        double settled = GFBsAuralisConfig.CLIENT.timelineSettledToleranceMs.get() / 1000.0;
        double hard = Math.max(settled + 0.001,
                GFBsAuralisConfig.CLIENT.timelineHardSeekThresholdMs.get() / 1000.0);
        return new TimelineCalibrationPolicy(
                settled,
                hard,
                GFBsAuralisConfig.CLIENT.timelineConvergenceSeconds.get(),
                GFBsAuralisConfig.CLIENT.timelineMaximumRateAdjustment.get());
    }

    private static void sendClockProbeIfDue() {
        if (clientTick < nextClockProbeTick) return;
        long now = System.nanoTime();
        try {
            NetworkHandler.CHANNEL.sendToServer(new ClientTimeProbePacket(++probeNonce, now));
            if (clockBootstrapProbesRemaining > 0) {
                nextClockProbeTick = clientTick + CLOCK_BOOTSTRAP_INTERVAL_TICKS;
                return;
            }
            scheduleMaintenanceProbe();
        } catch (Throwable failure) {
            nextClockProbeTick = clientTick + CLOCK_PROBE_RETRY_TICKS;
            GFBsAuralis.LOGGER.debug("Unable to send Auralis clock probe: {}", failure.getMessage());
        }
    }

    private static void scheduleMaintenanceProbe() {
        int maintenanceInterval = GFBsAuralisConfig.CLIENT.clockProbeIntervalTicks.get();
        nextClockProbeTick = maintenanceInterval == 0
                ? Long.MAX_VALUE : clientTick + maintenanceInterval;
    }

    private static void removeMirror(String id) {
        Mirror mirror = MIRRORS.remove(id);
        if (mirror != null) mirror.dispose();
    }

    private static void acknowledge(UUID operationId, AuthoritativeSoundSnapshot snapshot,
                                    ClientExecutionStatus status, String detail, double duration) {
        NetworkHandler.CHANNEL.sendToServer(new ClientSoundAckPacket(operationId, snapshot.id(), snapshot.epoch(),
                snapshot.revision(), status, detail, duration));
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private static final class Mirror {
        final String id;
        final long epoch;
        volatile long revision;
        volatile AuthoritativeSoundSnapshot authority;
        volatile AuralisSoundInstance instance;
        volatile AuralisSoundSpec createdFrom;
        volatile boolean recoveryQueued;
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        Mirror(String id, long epoch) { this.id = id; this.epoch = epoch; }

        CompletableFuture<AuralisSoundInstance> ensureInstance(AuralisSoundSpec spec) {
            if (instance != null && createdFrom != null
                    && createdFrom.soundEventId().equals(spec.soundEventId())
                    && createdFrom.streamed() == spec.streamed()) {
                return CompletableFuture.completedFuture(instance);
            }
            if (instance != null) {
                AuralisSoundInstance replaced = instance;
                instance = null;
                createdFrom = null;
                return replaced.dispose().future().thenComposeAsync(ignored -> createInstance(spec), Minecraft.getInstance());
            }
            return createInstance(spec);
        }

        private CompletableFuture<AuralisSoundInstance> createInstance(AuralisSoundSpec spec) {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(spec.soundEventId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown SoundEvent: " + spec.soundEventId()));
            AuralisOperation<AuralisSoundInstance> creation = spec.streamed()
                    ? AuralisApi.createStreamed(event) : AuralisApi.create(event);
            return creation.future().thenApply(created -> {
                instance = created;
                createdFrom = spec;
                return created;
            });
        }

        double duration() { return instance == null ? 0.0 : instance.getDurationSeconds(); }

        void dispose() {
            tail = tail.handle((ignored, failure) -> null).thenCompose(ignored -> {
                AuralisSoundInstance current = instance;
                instance = null;
                createdFrom = null;
                return current == null ? CompletableFuture.completedFuture(null)
                        : current.dispose().future().thenAccept(snapshot -> { });
            });
        }
    }

    private static final class SnapshotAssembly {
        final int chunkCount;
        final boolean full;
        final long createdNanos;
        final Map<Integer, List<AuthoritativeSoundSnapshot>> chunks = new HashMap<>();
        SnapshotAssembly(int chunkCount, boolean full, long createdNanos) {
            this.chunkCount = chunkCount; this.full = full; this.createdNanos = createdNanos;
        }
    }
}
