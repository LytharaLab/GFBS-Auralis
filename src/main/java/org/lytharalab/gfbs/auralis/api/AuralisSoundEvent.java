package org.lytharalab.gfbs.auralis.api;

public enum AuralisSoundEvent {
    /** Fired when a sound starts playing. */
    PLAY,
    /** Fired when a sound pauses. */
    PAUSE,
    /** Fired when a sound stops explicitly or naturally. */
    STOP,
    /** Fired when a sound is forcibly stopped. */
    FORCE_STOP,
    /** Fired when a logical voice receives a physical OpenAL source. */
    MATERIALIZED,
    /** Fired when a physical source is released while the logical voice remains. */
    VIRTUALIZED,
    /** Fired when a sound is evicted due to low priority. */
    EVICTED,
    /** Fired when the logical instance and all resources have been disposed. */
    DISPOSED
}
