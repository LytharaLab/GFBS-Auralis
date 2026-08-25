package org.lytharalab.gfbs.auralis.sync;

import org.lytharalab.gfbs.auralis.ClientSoundController;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.bus.AuralisAudioBus;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.network.AudioBusStatePacket;
import org.lytharalab.gfbs.auralis.network.AudioClockSyncRequestPacket;
import org.lytharalab.gfbs.auralis.network.AudioClockSyncResponsePacket;
import org.lytharalab.gfbs.auralis.network.AudioStateDeltaPacket;
import org.lytharalab.gfbs.auralis.network.AudioStateSnapshotPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;
import org.lytharalab.gfbs.auralis.network.sync.SyncedBusState;
import org.lytharalab.gfbs.auralis.network.sync.SyncedSoundState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Client-side clock calibration, snapshot assembly, and idempotent replication. */
public final class AudioSyncClient {
    private static final int INITIAL_PROBE_COUNT = 5;
    private static final long INITIAL_PROBE_INTERVAL_NANOS = 150_000_000L;
    private static final long PERIODIC_PROBE_INTERVAL_NANOS = 15_000_000_000L;
    private static final int MAX_PENDING_PROBES = 16;

    private static final AtomicBoolean CONNECTED = new AtomicBoolean(false);
    private static final AtomicBoolean BUS_DIRTY = new AtomicBoolean(false);
    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong(0L);
    private static final AtomicLong NEXT_BUS_RECONCILE_NANOS = new AtomicLong(0L);
    private static final Map<Long, Long> PENDING_PROBES = new ConcurrentHashMap<>();
    private static final ClockEstimator CLOCK = new ClockEstimator();
    private static final Object STATE_LOCK = new Object();

    private static UUID serverEpoch;
    private static int initialProbesRemaining;
    private static long nextProbeNanos;
    private static boolean initialSnapshotRequested;
    private static SnapshotAssembly snapshotAssembly;

    private static final Map<String, Long> SOUND_REVISIONS = new HashMap<>();
    private static final Map<String, SyncedSoundState> SOUND_STATES = new HashMap<>();
    private static final Map<String, Long> BUS_REVISIONS = new HashMap<>();
    private static final Map<String, SyncedBusState> BUS_STATES = new HashMap<>();
    private static final Set<String> SYNC_CREATED_BUSES = new HashSet<>();
    private static final Map<String, BusBaseline> SYNC_BUS_BASELINES = new HashMap<>();

    private AudioSyncClient() {
    }

    public static void onConnected() {
        resetSynchronizedBuses();
        synchronized (STATE_LOCK) {
            CONNECTED.set(true);
            PENDING_PROBES.clear();
            CLOCK.reset();
            serverEpoch = null;
            initialProbesRemaining = INITIAL_PROBE_COUNT;
            nextProbeNanos = 0L;
            initialSnapshotRequested = false;
            snapshotAssembly = null;
            SOUND_REVISIONS.clear();
            SOUND_STATES.clear();
            BUS_REVISIONS.clear();
            BUS_STATES.clear();
            BUS_DIRTY.set(false);
            NEXT_BUS_RECONCILE_NANOS.set(0L);
        }
        ClientSoundController.resetAuthoritativeStates();
    }

    public static void onDisconnected() {
        synchronized (STATE_LOCK) {
            CONNECTED.set(false);
            PENDING_PROBES.clear();
            serverEpoch = null;
            snapshotAssembly = null;
            SOUND_REVISIONS.clear();
            SOUND_STATES.clear();
            BUS_REVISIONS.clear();
            BUS_STATES.clear();
            CLOCK.reset();
            BUS_DIRTY.set(false);
            NEXT_BUS_RECONCILE_NANOS.set(0L);
        }
        resetSynchronizedBuses();
        ClientSoundController.resetAuthoritativeStates();
    }

    /** Called once per client tick; no decoding or OpenAL work occurs here. */
    public static void tick() {
        if (!CONNECTED.get()) return;
        long clientNow = System.nanoTime();
        boolean shouldProbe;
        boolean requestSnapshot = false;
        synchronized (STATE_LOCK) {
            shouldProbe = clientNow >= nextProbeNanos;
            if (shouldProbe) {
                if (initialProbesRemaining > 0) {
                    initialProbesRemaining--;
                    if (!initialSnapshotRequested && initialProbesRemaining <= INITIAL_PROBE_COUNT - 3) {
                        initialSnapshotRequested = true;
                        requestSnapshot = true;
                    }
                    nextProbeNanos = clientNow + INITIAL_PROBE_INTERVAL_NANOS;
                } else {
                    nextProbeNanos = clientNow + PERIODIC_PROBE_INTERVAL_NANOS;
                }
            }
        }
        if (shouldProbe) sendClockProbe(clientNow, requestSnapshot);
        ClientSoundController.tickAuthoritativeStates(estimateServerNowNanos(clientNow));
        if (BUS_DIRTY.get() && clientNow >= NEXT_BUS_RECONCILE_NANOS.get()) {
            reconcileBuses();
        }
    }

    public static long estimateServerNowNanos() {
        return estimateServerNowNanos(System.nanoTime());
    }

    public static long estimateServerNowNanos(long clientNowNanos) {
        return CLOCK.toServerTime(clientNowNanos);
    }

    public static void acceptClockResponse(
            AudioClockSyncResponsePacket packet,
            long clientReceiveNanos
    ) {
        Long sent = PENDING_PROBES.remove(packet.requestId());
        if (sent == null || sent.longValue() != packet.clientSendNanos()) return;
        CLOCK.accept(
                packet.clientSendNanos(),
                packet.serverReceiveNanos(),
                packet.serverSendNanos(),
                clientReceiveNanos
        );
    }

    public static void acceptSoundDelta(AudioStateDeltaPacket packet) {
        observeServerTimestamp(packet.serverSendNanos());
        synchronized (STATE_LOCK) {
            ensureEpoch(packet.serverEpoch());
            long previous = SOUND_REVISIONS.getOrDefault(packet.soundId(), -1L);
            if (packet.revision() <= previous) return;
            SOUND_REVISIONS.put(packet.soundId(), packet.revision());
            if (packet.action() == AudioStateDeltaPacket.Action.REMOVE) {
                SOUND_STATES.remove(packet.soundId());
                ClientSoundController.removeAuthoritativeState(packet.soundId());
            } else {
                SOUND_STATES.put(packet.soundId(), packet.state());
                ClientSoundController.applyAuthoritativeState(
                        packet.state(), estimateServerNowNanos()
                );
            }
        }
    }

    public static void acceptBusDelta(AudioBusStatePacket packet) {
        observeServerTimestamp(packet.serverSendNanos());
        synchronized (STATE_LOCK) {
            ensureEpoch(packet.serverEpoch());
            SyncedBusState state = packet.state();
            long previous = BUS_REVISIONS.getOrDefault(state.name(), -1L);
            if (state.revision() <= previous) return;
            BUS_REVISIONS.put(state.name(), state.revision());
            BUS_STATES.put(state.name(), state);
            markBusesDirty();
        }
        reconcileBuses();
    }

    public static void acceptSnapshotChunk(AudioStateSnapshotPacket packet) {
        observeServerTimestamp(packet.serverSendNanos());
        synchronized (STATE_LOCK) {
            ensureEpoch(packet.serverEpoch());
            if (snapshotAssembly == null
                    || snapshotAssembly.snapshotId != packet.snapshotId()
                    || snapshotAssembly.chunkCount != packet.chunkCount()) {
                snapshotAssembly = new SnapshotAssembly(
                        packet.snapshotId(), packet.snapshotRevision(), packet.chunkCount()
                );
            }
            if (!snapshotAssembly.accept(packet)) return;
            if (!snapshotAssembly.complete()) return;

            SnapshotAssembly completed = snapshotAssembly;
            snapshotAssembly = null;
            applySnapshot(completed);
        }
    }

    private static void applySnapshot(SnapshotAssembly snapshot) {
        Map<String, SyncedBusState> buses = new HashMap<>();
        Map<String, SyncedSoundState> sounds = new HashMap<>();
        for (List<SyncedBusState> chunk : snapshot.busChunks) {
            if (chunk != null) {
                for (SyncedBusState state : chunk) buses.put(state.name(), state);
            }
        }
        for (List<SyncedSoundState> chunk : snapshot.soundChunks) {
            if (chunk != null) {
                for (SyncedSoundState state : chunk) sounds.put(state.id(), state);
            }
        }

        for (SyncedBusState state : buses.values()) {
            long previous = BUS_REVISIONS.getOrDefault(state.name(), -1L);
            if (state.revision() >= previous) {
                BUS_REVISIONS.put(state.name(), state.revision());
                BUS_STATES.put(state.name(), state);
            }
        }
        for (String known : new ArrayList<>(BUS_REVISIONS.keySet())) {
            if (!buses.containsKey(known)
                    && BUS_REVISIONS.getOrDefault(known, -1L) <= snapshot.snapshotRevision) {
                BUS_REVISIONS.put(known, snapshot.snapshotRevision);
                BUS_STATES.remove(known);
                restoreOrRemoveSynchronizedBus(known);
            }
        }
        markBusesDirty();
        reconcileBuses();

        long serverNow = estimateServerNowNanos();
        for (SyncedSoundState state : sounds.values()) {
            long previous = SOUND_REVISIONS.getOrDefault(state.id(), -1L);
            if (state.revision() >= previous) {
                SOUND_REVISIONS.put(state.id(), state.revision());
                SOUND_STATES.put(state.id(), state);
                ClientSoundController.applyAuthoritativeState(state, serverNow);
            }
        }
        // Include revision-only tombstones as well as live states. Otherwise a
        // delayed pre-snapshot UPSERT could resurrect an id that was already
        // removed before the snapshot was captured.
        for (String known : new ArrayList<>(SOUND_REVISIONS.keySet())) {
            if (!sounds.containsKey(known)
                    && SOUND_REVISIONS.getOrDefault(known, -1L) <= snapshot.snapshotRevision) {
                SOUND_REVISIONS.put(known, snapshot.snapshotRevision);
                SOUND_STATES.remove(known);
                ClientSoundController.removeAuthoritativeState(known);
            }
        }
    }

    private static void sendClockProbe(long clientSendNanos, boolean requestSnapshot) {
        if (PENDING_PROBES.size() >= MAX_PENDING_PROBES) {
            long oldest = PENDING_PROBES.keySet().stream().min(Long::compareTo).orElse(-1L);
            if (oldest >= 0L) PENDING_PROBES.remove(oldest);
        }
        long requestId = NEXT_REQUEST_ID.incrementAndGet();
        PENDING_PROBES.put(requestId, clientSendNanos);
        try {
            NetworkHandler.CHANNEL.sendToServer(
                    new AudioClockSyncRequestPacket(requestId, clientSendNanos, requestSnapshot)
            );
        } catch (Throwable sendFailure) {
            PENDING_PROBES.remove(requestId);
            GFBsAuralis.LOGGER.debug("Auralis clock probe could not be sent: {}", sendFailure.getMessage());
        }
    }

    private static void observeServerTimestamp(long serverSendNanos) {
        CLOCK.acceptFallback(serverSendNanos, System.nanoTime());
    }

    private static void ensureEpoch(UUID epoch) {
        if (epoch.equals(serverEpoch)) return;
        serverEpoch = epoch;
        snapshotAssembly = null;
        SOUND_REVISIONS.clear();
        SOUND_STATES.clear();
        BUS_REVISIONS.clear();
        BUS_STATES.clear();
        BUS_DIRTY.set(false);
        NEXT_BUS_RECONCILE_NANOS.set(0L);
        resetSynchronizedBuses();
        ClientSoundController.resetAuthoritativeStates();
    }

    private static void reconcileBuses() {
        if (!BUS_DIRTY.get()) return;
        long now = System.nanoTime();
        if (now < NEXT_BUS_RECONCILE_NANOS.get()) return;
        if (!AuralisApi.isInitialized()) {
            NEXT_BUS_RECONCILE_NANOS.set(now + 250_000_000L);
            return;
        }
        BUS_DIRTY.set(false);
        NEXT_BUS_RECONCILE_NANOS.set(0L);
        boolean retry = false;
        List<SyncedBusState> states;
        synchronized (STATE_LOCK) {
            states = List.copyOf(BUS_STATES.values());
        }
        states = normalizeBusParents(states);
        var buses = AuralisApi.buses();
        for (SyncedBusState state : states) {
            if (!AudioBusSystem.MASTER.equals(state.name()) || state.removed()) continue;
            try {
                var master = buses.requireBus(AudioBusSystem.MASTER);
                rememberBusBaseline(master);
                master.setVolume(state.volume());
                master.setMuted(state.muted());
                master.setSolo(state.solo());
                master.setEffectsBypassed(state.effectsBypassed());
            } catch (Throwable failure) {
                retry = true;
                GFBsAuralis.LOGGER.debug("Unable to apply synchronized Master bus state: {}", failure.getMessage());
            }
        }
        List<SyncedBusState> active = states.stream()
                .filter(state -> !state.removed() && !AudioBusSystem.MASTER.equals(state.name()))
                .sorted(Comparator.comparingLong(SyncedBusState::revision))
                .toList();
        List<SyncedBusState> pending = new ArrayList<>(active);
        for (int pass = 0; pass <= active.size() && !pending.isEmpty(); pass++) {
            boolean progressed = false;
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                SyncedBusState state = iterator.next();
                if (!AudioBusSystem.MASTER.equals(state.parent())
                        && buses.findBus(state.parent()).isEmpty()) {
                    continue;
                }
                try {
                    var existing = buses.findBus(state.name());
                    if (existing.isEmpty()) {
                        buses.createBus(state.name(), state.parent());
                        synchronized (STATE_LOCK) {
                            SYNC_CREATED_BUSES.add(state.name());
                        }
                    } else {
                        rememberBusBaseline(existing.get());
                    }
                    var bus = buses.requireBus(state.name());
                    bus.setParent(state.parent());
                    bus.setVolume(state.volume());
                    bus.setMuted(state.muted());
                    bus.setSolo(state.solo());
                    bus.setEffectsBypassed(state.effectsBypassed());
                    iterator.remove();
                    progressed = true;
                } catch (Throwable failure) {
                    GFBsAuralis.LOGGER.debug(
                            "Deferred synchronized Auralis bus {}: {}", state.name(), failure.getMessage()
                    );
                }
            }
            if (!progressed) {
                retry = true;
                break;
            }
        }
        for (SyncedBusState state : states) {
            if (!state.removed() || AudioBusSystem.MASTER.equals(state.name())) continue;
            try {
                restoreOrRemoveSynchronizedBus(state.name());
            } catch (Throwable failure) {
                retry = true;
                GFBsAuralis.LOGGER.debug(
                        "Unable to remove synchronized Auralis bus {}: {}", state.name(), failure.getMessage()
                );
            }
        }
        if (retry) {
            BUS_DIRTY.set(true);
            NEXT_BUS_RECONCILE_NANOS.set(System.nanoTime() + 1_000_000_000L);
        }
    }

    /** Apply the same removed-parent and cycle rules to real-time bus deltas as snapshots. */
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
            Set<String> visited = new HashSet<>();
            visited.add(state.name());
            boolean cycle = false;
            boolean resolvingRemovedPrefix = true;
            for (int depth = 0; depth <= states.size(); depth++) {
                if (AudioBusSystem.MASTER.equals(cursor)) break;
                if (!visited.add(cursor)) {
                    cycle = true;
                    break;
                }
                SyncedBusState parent = byName.get(cursor);
                if (parent == null) break;
                if (parent.removed() && resolvingRemovedPrefix) {
                    desiredParent = parent.parent();
                } else if (!parent.removed()) {
                    resolvingRemovedPrefix = false;
                }
                cursor = parent.parent();
            }
            if (cycle) desiredParent = AudioBusSystem.MASTER;
            normalized.add(desiredParent.equals(state.parent()) ? state : new SyncedBusState(
                    state.revision(), state.name(), desiredParent, state.volume(),
                    state.muted(), state.solo(), state.effectsBypassed(), false
            ));
        }
        return normalized;
    }

    private static void markBusesDirty() {
        BUS_DIRTY.set(true);
        NEXT_BUS_RECONCILE_NANOS.set(0L);
    }

    private static void resetSynchronizedBuses() {
        if (!AuralisApi.isInitialized()) {
            synchronized (STATE_LOCK) {
                SYNC_CREATED_BUSES.clear();
                SYNC_BUS_BASELINES.clear();
            }
            return;
        }
        Set<String> created;
        Map<String, BusBaseline> baselines;
        synchronized (STATE_LOCK) {
            created = Set.copyOf(SYNC_CREATED_BUSES);
            baselines = Map.copyOf(SYNC_BUS_BASELINES);
            SYNC_CREATED_BUSES.clear();
            SYNC_BUS_BASELINES.clear();
        }
        var buses = AuralisApi.buses();
        for (Map.Entry<String, BusBaseline> entry : baselines.entrySet()) {
            restoreBusBaseline(buses, entry.getKey(), entry.getValue());
        }
        for (String name : created) {
            try {
                buses.removeBus(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void rememberBusBaseline(AuralisAudioBus bus) {
        synchronized (STATE_LOCK) {
            if (SYNC_CREATED_BUSES.contains(bus.getName())) return;
            SYNC_BUS_BASELINES.computeIfAbsent(bus.getName(), ignored -> new BusBaseline(
                    bus.getParentName(), bus.getVolume(), bus.isMuted(),
                    bus.isSolo(), bus.isEffectsBypassed()
            ));
        }
    }

    private static void restoreOrRemoveSynchronizedBus(String name) {
        if (!AuralisApi.isInitialized()) return;
        boolean created;
        BusBaseline baseline;
        synchronized (STATE_LOCK) {
            created = SYNC_CREATED_BUSES.remove(name);
            baseline = SYNC_BUS_BASELINES.remove(name);
        }
        var buses = AuralisApi.buses();
        if (created) {
            try {
                buses.removeBus(name);
            } catch (Throwable ignored) {
            }
        } else if (baseline != null) {
            restoreBusBaseline(buses, name, baseline);
        }
    }

    private static void restoreBusBaseline(
            AudioBusSystem buses,
            String name,
            BusBaseline baseline
    ) {
        try {
            var bus = buses.findBus(name).orElse(null);
            if (bus == null) return;
            if (!AudioBusSystem.MASTER.equals(name)
                    && buses.findBus(baseline.parent()).isPresent()) {
                bus.setParent(baseline.parent());
            }
            bus.setVolume(baseline.volume());
            bus.setMuted(baseline.muted());
            bus.setSolo(baseline.solo());
            bus.setEffectsBypassed(baseline.effectsBypassed());
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.debug(
                    "Unable to restore local Auralis bus {} after synchronization: {}",
                    name, failure.getMessage()
            );
        }
    }

    private static final class SnapshotAssembly {
        private final long snapshotId;
        private final long snapshotRevision;
        private final int chunkCount;
        private final boolean[] received;
        private final List<SyncedBusState>[] busChunks;
        private final List<SyncedSoundState>[] soundChunks;

        @SuppressWarnings("unchecked")
        private SnapshotAssembly(long snapshotId, long snapshotRevision, int chunkCount) {
            this.snapshotId = snapshotId;
            this.snapshotRevision = snapshotRevision;
            this.chunkCount = chunkCount;
            this.received = new boolean[chunkCount];
            this.busChunks = (List<SyncedBusState>[]) new List<?>[chunkCount];
            this.soundChunks = (List<SyncedSoundState>[]) new List<?>[chunkCount];
        }

        private boolean accept(AudioStateSnapshotPacket packet) {
            int index = packet.chunkIndex();
            if (received[index]) return false;
            received[index] = true;
            busChunks[index] = packet.buses();
            soundChunks[index] = packet.sounds();
            return true;
        }

        private boolean complete() {
            for (boolean chunkReceived : received) {
                if (!chunkReceived) return false;
            }
            return true;
        }
    }

    private record BusBaseline(
            String parent,
            float volume,
            boolean muted,
            boolean solo,
            boolean effectsBypassed
    ) {
    }

    private static final class ClockEstimator {
        private boolean initialized;
        private long offsetNanos;
        private long bestRoundTripNanos = Long.MAX_VALUE;

        private synchronized void reset() {
            initialized = false;
            offsetNanos = 0L;
            bestRoundTripNanos = Long.MAX_VALUE;
        }

        private synchronized void accept(long t1, long t2, long t3, long t4) {
            if (t4 < t1 || t3 < t2) return;
            long roundTrip = Math.max(0L, (t4 - t1) - (t3 - t2));
            double offset = ((t2 - (double) t1) + (t3 - (double) t4)) * 0.5;
            long candidate = saturatingLong(offset);
            if (!initialized || roundTrip < bestRoundTripNanos) {
                offsetNanos = candidate;
                bestRoundTripNanos = roundTrip;
                initialized = true;
            } else if (roundTrip <= bestRoundTripNanos + Math.max(1_000_000L, bestRoundTripNanos / 2L)) {
                offsetNanos = saturatingLong(offsetNanos * 0.8 + candidate * 0.2);
            }
        }

        private synchronized void acceptFallback(long serverSendNanos, long clientReceiveNanos) {
            if (initialized) return;
            offsetNanos = serverSendNanos - clientReceiveNanos;
            initialized = true;
        }

        private synchronized long toServerTime(long clientNanos) {
            long result = clientNanos + offsetNanos;
            if (((clientNanos ^ result) & (offsetNanos ^ result)) < 0) {
                return offsetNanos > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            }
            return result;
        }

        private static long saturatingLong(double value) {
            if (value >= Long.MAX_VALUE) return Long.MAX_VALUE;
            if (value <= Long.MIN_VALUE) return Long.MIN_VALUE;
            return (long) value;
        }
    }
}
