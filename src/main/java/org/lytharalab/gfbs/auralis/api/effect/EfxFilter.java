package org.lytharalab.gfbs.auralis.api.effect;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable configuration for one OpenAL EFX low/high/band-pass filter. */
public final class EfxFilter {
    private final String id;
    private final EfxFilterType type;
    private final AtomicLong revision = new AtomicLong(1L);
    private final Object lock = new Object();
    private final EnumMap<EfxFilterParameter, Float> parameters = new EnumMap<>(EfxFilterParameter.class);

    public EfxFilter(String id, EfxFilterType type) {
        this.id = validateId(id);
        this.type = Objects.requireNonNull(type, "type");
        for (EfxFilterParameter parameter : EfxFilterParameter.values()) {
            if (parameter.filterType() == type) parameters.put(parameter, 1.0f);
        }
    }

    public String getId() { return id; }
    public EfxFilterType getType() { return type; }
    public long getRevision() { return revision.get(); }

    public EfxFilter set(EfxFilterParameter parameter, float value) {
        Objects.requireNonNull(parameter, "parameter");
        if (parameter.filterType() != type) {
            throw new IllegalArgumentException(parameter + " belongs to " + parameter.filterType() + ", not " + type);
        }
        float finite = Float.isFinite(value) ? value : 1.0f;
        Float clamped = Math.max(0.0f, Math.min(1.0f, finite));
        synchronized (lock) {
            if (!clamped.equals(parameters.put(parameter, clamped))) {
                revision.updateAndGet(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
            }
        }
        return this;
    }

    public Map<EfxFilterParameter, Float> parameters() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new EnumMap<>(parameters));
        }
    }

    private static String validateId(String id) {
        String value = Objects.requireNonNull(id, "id").trim();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Filter id must be a namespaced id (namespace:path): " + id);
        }
        return value;
    }
}
