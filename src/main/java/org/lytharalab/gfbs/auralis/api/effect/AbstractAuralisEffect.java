package org.lytharalab.gfbs.auralis.api.effect;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe base implementation for mutable effect configuration. */
public abstract class AbstractAuralisEffect implements AuralisEffect {
    private final String id;
    private final AtomicLong revision = new AtomicLong(1L);
    private volatile boolean enabled = true;
    private volatile float wet = 1.0f;

    protected AbstractAuralisEffect(String id) {
        this.id = validateId(id);
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final boolean isEnabled() {
        return enabled;
    }

    @Override
    public AuralisEffect setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markChanged();
        }
        return this;
    }

    @Override
    public final float getWet() {
        return wet;
    }

    @Override
    public AuralisEffect setWet(float wet) {
        float value = Float.isFinite(wet) ? Math.max(0.0f, Math.min(1.0f, wet)) : 0.0f;
        if (Float.compare(this.wet, value) != 0) {
            this.wet = value;
            markChanged();
        }
        return this;
    }

    @Override
    public final long getRevision() {
        return revision.get();
    }

    protected final void markChanged() {
        revision.updateAndGet(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }

    private static String validateId(String id) {
        String value = Objects.requireNonNull(id, "id").trim();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Effect id must be a namespaced id (namespace:path): " + id);
        }
        return value;
    }
}
