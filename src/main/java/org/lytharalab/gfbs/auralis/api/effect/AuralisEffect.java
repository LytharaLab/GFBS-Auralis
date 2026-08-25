package org.lytharalab.gfbs.auralis.api.effect;

/**
 * A mutable effect instance that can be inserted into an audio bus.
 *
 * <p>Effects are identity objects. A caller should create a separate instance
 * when two buses require independent parameters. Implementations must increase
 * {@link #getRevision()} whenever a property that changes rendering is updated.
 * This lets the engine keep the normal tick path allocation-free and only
 * touch OpenAL when an effect actually changes.</p>
 */
public interface AuralisEffect extends AutoCloseable {
    /** Stable, namespaced instance id, for example {@code example:reactor_reverb}. */
    String getId();

    /** Backend used to render this effect. */
    EffectBackend getBackend();

    boolean isEnabled();

    AuralisEffect setEnabled(boolean enabled);

    /** Wet contribution in the inclusive range 0..1. */
    float getWet();

    AuralisEffect setWet(float wet);

    /** Monotonically increasing configuration revision. */
    long getRevision();

    /**
     * Releases implementation-owned resources. Core EFX resources are owned by
     * Auralis and are released independently; most effects therefore need no-op
     * cleanup.
     */
    @Override
    default void close() {
    }
}
