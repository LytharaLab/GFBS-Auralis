package org.lytharalab.gfbs.auralis.core.bus;

import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusView;
import org.lytharalab.gfbs.auralis.api.bus.AuralisAudioBus;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffect;
import org.lytharalab.gfbs.auralis.api.effect.EfxDirectFilterEffect;
import org.lytharalab.gfbs.auralis.api.effect.EfxEffect;
import org.lytharalab.gfbs.auralis.api.effect.PcmEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe hierarchical bus graph and immutable route compiler. */
public final class AuralisBusManager implements AudioBusSystem {
    private final Object lock = new Object();
    private final LinkedHashMap<String, BusImpl> buses = new LinkedHashMap<>();
    private final AtomicLong publishedRevision = new AtomicLong(1L);
    private volatile long lastFingerprint = Long.MIN_VALUE;
    private volatile BusMixSnapshot lastSnapshot;

    public AuralisBusManager() {
        BusImpl master = new BusImpl(MASTER, null);
        buses.put(MASTER, master);
        lastSnapshot = compileSnapshot();
    }

    @Override
    public AuralisAudioBus master() {
        synchronized (lock) {
            return buses.get(MASTER);
        }
    }

    @Override
    public AuralisAudioBus createBus(String name) {
        return createBus(name, MASTER);
    }

    @Override
    public AuralisAudioBus createBus(String name, String parentName) {
        String busName = validateName(name);
        String parent = validateName(parentName);
        synchronized (lock) {
            if (MASTER.equals(busName)) return buses.get(MASTER);
            if (buses.containsKey(busName)) {
                throw new IllegalArgumentException("Auralis bus already exists: " + busName);
            }
            requireBusLocked(parent);
            BusImpl created = new BusImpl(busName, parent);
            buses.put(busName, created);
            invalidate();
            return created;
        }
    }

    @Override
    public Optional<AuralisAudioBus> findBus(String name) {
        if (name == null) return Optional.empty();
        synchronized (lock) {
            return Optional.ofNullable(buses.get(name.trim())).map(bus -> (AuralisAudioBus) bus);
        }
    }

    @Override
    public boolean removeBus(String name) {
        String busName = validateName(name);
        if (MASTER.equals(busName)) return false;
        synchronized (lock) {
            BusImpl removed = buses.remove(busName);
            if (removed == null) return false;
            String replacementParent = removed.parentName == null ? MASTER : removed.parentName;
            for (BusImpl bus : buses.values()) {
                if (busName.equals(bus.parentName)) {
                    bus.parentName = replacementParent;
                    bus.bumpRevision();
                }
            }
            invalidate();
            return true;
        }
    }

    @Override
    public List<AuralisAudioBus> buses() {
        synchronized (lock) {
            return List.copyOf(buses.values());
        }
    }

    @Override
    public void reset() {
        synchronized (lock) {
            BusImpl master = buses.get(MASTER);
            buses.clear();
            master.resetState();
            buses.put(MASTER, master);
            invalidate();
        }
    }

    public BusMixSnapshot snapshot() {
        synchronized (lock) {
            long fingerprint = fingerprintLocked();
            BusMixSnapshot cached = lastSnapshot;
            if (cached != null && lastFingerprint == fingerprint) return cached;
            lastFingerprint = fingerprint;
            lastSnapshot = compileSnapshot();
            return lastSnapshot;
        }
    }

    @Override
    public AudioBusView view(String busName) {
        String name = validateName(busName);
        synchronized (lock) {
            requireBusLocked(name);
            CompiledBusRoute route = snapshot().route(name);
            return new AudioBusView(
                    route.busName(),
                    route.routeToMaster(),
                    route.gain(),
                    route.audible(),
                    route.effects(),
                    route.signature()
            );
        }
    }

    private BusMixSnapshot compileSnapshot() {
        synchronized (lock) {
            boolean anySolo = buses.values().stream().anyMatch(bus -> bus.solo);
            Map<String, CompiledBusRoute> compiled = new LinkedHashMap<>();
            for (String name : buses.keySet()) {
                compiled.put(name, compileRouteLocked(name, anySolo));
            }
            return new BusMixSnapshot(publishedRevision.incrementAndGet(), compiled);
        }
    }

    private CompiledBusRoute compileRouteLocked(String name, boolean anySolo) {
        List<String> routeNames = new ArrayList<>();
        List<AuralisEffect> effects = new ArrayList<>();
        List<PcmEffect> pcmEffects = new ArrayList<>();
        float gain = 1.0f;
        boolean muted = false;
        boolean soloInRoute = false;
        long signature = 0xcbf29ce484222325L;
        long effectSignature = mix(0x84222325cbf29ce4L, name.hashCode());
        long pcmSignature = mix(0x517cc1b727220a95L, name.hashCode());

        BusImpl cursor = requireBusLocked(name);
        int guard = 0;
        while (cursor != null) {
            if (++guard > buses.size() + 1) {
                throw new IllegalStateException("Cycle detected while compiling Auralis bus: " + name);
            }
            routeNames.add(cursor.name);
            gain = clampGain(gain * cursor.volume);
            muted |= cursor.muted;
            soloInRoute |= cursor.solo;
            signature = mix(signature, cursor.revision.get());
            signature = mix(signature, Float.floatToIntBits(cursor.volume));
            signature = mix(signature, cursor.muted ? 1L : 0L);
            signature = mix(signature, cursor.solo ? 1L : 0L);
            signature = mix(signature, cursor.effectsBypassed ? 1L : 0L);
            effectSignature = mix(effectSignature, cursor.effectsBypassed ? 1L : 0L);
            pcmSignature = mix(pcmSignature, cursor.effectsBypassed ? 1L : 0L);

            if (!cursor.effectsBypassed) {
                for (AuralisEffect effect : cursor.effects) {
                    effects.add(effect);
                    if (effect instanceof PcmEffect pcmEffect) pcmEffects.add(pcmEffect);
                    signature = mix(signature, System.identityHashCode(effect));
                    signature = mix(signature, safeEffectRevision(effect));
                    effectSignature = mix(effectSignature, System.identityHashCode(effect));
                    effectSignature = mix(effectSignature, safeEffectRevision(effect));
                    if (effect instanceof PcmEffect) {
                        pcmSignature = mix(pcmSignature, System.identityHashCode(effect));
                        pcmSignature = mix(pcmSignature, safeEffectRevision(effect));
                    }
                    if (effect instanceof EfxEffect efx && efx.getSendFilter() != null) {
                        signature = mix(signature, efx.getSendFilter().getRevision());
                        effectSignature = mix(effectSignature, efx.getSendFilter().getRevision());
                    } else if (effect instanceof EfxDirectFilterEffect direct) {
                        signature = mix(signature, direct.getFilter().getRevision());
                        effectSignature = mix(effectSignature, direct.getFilter().getRevision());
                    }
                }
            }

            cursor = cursor.parentName == null ? null : requireBusLocked(cursor.parentName);
        }

        boolean audible = !muted && (!anySolo || soloInRoute);
        return new CompiledBusRoute(
                name,
                routeNames,
                audible ? gain : 0.0f,
                audible,
                effects,
                pcmEffects,
                signature,
                effectSignature,
                pcmSignature
        );
    }

    private long fingerprintLocked() {
        long fingerprint = 0x9e3779b97f4a7c15L;
        for (BusImpl bus : buses.values()) {
            fingerprint = mix(fingerprint, bus.name.hashCode());
            fingerprint = mix(fingerprint, bus.parentName == null ? 0 : bus.parentName.hashCode());
            fingerprint = mix(fingerprint, bus.revision.get());
            for (AuralisEffect effect : bus.effects) {
                fingerprint = mix(fingerprint, System.identityHashCode(effect));
                fingerprint = mix(fingerprint, safeEffectRevision(effect));
                if (effect instanceof EfxEffect efx && efx.getSendFilter() != null) {
                    fingerprint = mix(fingerprint, efx.getSendFilter().getRevision());
                } else if (effect instanceof EfxDirectFilterEffect direct) {
                    fingerprint = mix(fingerprint, direct.getFilter().getRevision());
                }
            }
        }
        return fingerprint;
    }

    private void setParent(BusImpl bus, String newParentName) {
        String parentName = validateName(newParentName);
        synchronized (lock) {
            bus.requireActiveLocked();
            if (MASTER.equals(bus.name)) {
                throw new IllegalStateException("Master cannot be routed to another bus");
            }
            requireBusLocked(parentName);
            if (bus.name.equals(parentName)) {
                throw new IllegalArgumentException("A bus cannot route to itself: " + bus.name);
            }
            BusImpl cursor = requireBusLocked(parentName);
            int guard = 0;
            while (cursor != null) {
                if (cursor == bus) throw new IllegalArgumentException("Bus routing would create a cycle: " + bus.name);
                if (++guard > buses.size() + 1) throw new IllegalStateException("Existing bus graph contains a cycle");
                cursor = cursor.parentName == null ? null : requireBusLocked(cursor.parentName);
            }
            if (!parentName.equals(bus.parentName)) {
                bus.parentName = parentName;
                bus.bumpRevision();
                invalidate();
            }
        }
    }

    private BusImpl requireBusLocked(String name) {
        BusImpl bus = buses.get(name);
        if (bus == null) throw new IllegalArgumentException("Unknown Auralis bus: " + name);
        return bus;
    }

    private void invalidate() {
        lastFingerprint = Long.MIN_VALUE;
    }

    private static String validateName(String name) {
        String value = Objects.requireNonNull(name, "name").trim();
        if (value.isEmpty() || value.length() > 96 || !value.matches("[A-Za-z0-9_.:/-]+")) {
            throw new IllegalArgumentException("Invalid Auralis bus name: " + name);
        }
        return value;
    }

    private static float clampGain(float gain) {
        if (!Float.isFinite(gain)) return 0.0f;
        return Math.max(0.0f, Math.min(16.0f, gain));
    }

    private static long safeEffectRevision(AuralisEffect effect) {
        try {
            return effect.getRevision();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private final class BusImpl implements AuralisAudioBus {
        private final String name;
        private final AtomicLong revision = new AtomicLong(1L);
        private final List<AuralisEffect> effects = new ArrayList<>();
        private volatile String parentName;
        private volatile float volume = 1.0f;
        private volatile boolean muted;
        private volatile boolean solo;
        private volatile boolean effectsBypassed;

        private BusImpl(String name, String parentName) {
            this.name = name;
            this.parentName = parentName;
        }

        @Override public String getName() { return name; }
        @Override public String getParentName() { return parentName; }

        @Override
        public AuralisAudioBus setParent(String parentName) {
            AuralisBusManager.this.setParent(this, parentName);
            return this;
        }

        @Override public float getVolume() { return volume; }

        @Override
        public AuralisAudioBus setVolume(float volume) {
            float value = clampGain(volume);
            synchronized (lock) {
                requireActiveLocked();
                if (Float.compare(this.volume, value) != 0) {
                    this.volume = value;
                    bumpRevision();
                    invalidate();
                }
            }
            return this;
        }

        @Override public boolean isMuted() { return muted; }

        @Override
        public AuralisAudioBus setMuted(boolean muted) {
            synchronized (lock) {
                requireActiveLocked();
                if (this.muted != muted) {
                    this.muted = muted;
                    bumpRevision();
                    invalidate();
                }
            }
            return this;
        }

        @Override public boolean isSolo() { return solo; }

        @Override
        public AuralisAudioBus setSolo(boolean solo) {
            synchronized (lock) {
                requireActiveLocked();
                if (this.solo != solo) {
                    this.solo = solo;
                    bumpRevision();
                    invalidate();
                }
            }
            return this;
        }

        @Override public boolean isEffectsBypassed() { return effectsBypassed; }

        @Override
        public AuralisAudioBus setEffectsBypassed(boolean bypassed) {
            synchronized (lock) {
                requireActiveLocked();
                if (effectsBypassed != bypassed) {
                    effectsBypassed = bypassed;
                    bumpRevision();
                    invalidate();
                }
            }
            return this;
        }

        @Override
        public List<AuralisEffect> getEffects() {
            synchronized (lock) {
                return List.copyOf(effects);
            }
        }

        @Override
        public AuralisAudioBus addEffect(AuralisEffect effect) {
            return insertEffect(Integer.MAX_VALUE, effect);
        }

        @Override
        public AuralisAudioBus insertEffect(int index, AuralisEffect effect) {
            Objects.requireNonNull(effect, "effect");
            synchronized (lock) {
                requireActiveLocked();
                if (effects.contains(effect)) {
                    throw new IllegalArgumentException("Effect is already on bus " + name + ": " + effect.getId());
                }
                effects.add(Math.max(0, Math.min(index, effects.size())), effect);
                bumpRevision();
                invalidate();
            }
            return this;
        }

        @Override
        public boolean removeEffect(AuralisEffect effect) {
            synchronized (lock) {
                requireActiveLocked();
                boolean removed = effects.remove(effect);
                if (removed) {
                    bumpRevision();
                    invalidate();
                }
                return removed;
            }
        }

        @Override
        public AuralisEffect removeEffect(int index) {
            synchronized (lock) {
                requireActiveLocked();
                AuralisEffect removed = effects.remove(index);
                bumpRevision();
                invalidate();
                return removed;
            }
        }

        @Override
        public AuralisAudioBus moveEffect(int fromIndex, int toIndex) {
            synchronized (lock) {
                requireActiveLocked();
                AuralisEffect effect = effects.remove(fromIndex);
                effects.add(Math.max(0, Math.min(toIndex, effects.size())), effect);
                bumpRevision();
                invalidate();
            }
            return this;
        }

        @Override public long getRevision() { return revision.get(); }

        private void bumpRevision() {
            revision.updateAndGet(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
        }

        private void requireActiveLocked() {
            if (buses.get(name) != this) {
                throw new IllegalStateException("Auralis bus is no longer active: " + name);
            }
        }

        private void resetState() {
            parentName = null;
            volume = 1.0f;
            muted = false;
            solo = false;
            effectsBypassed = false;
            effects.clear();
            bumpRevision();
        }
    }
}
