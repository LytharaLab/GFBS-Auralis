package org.lytharalab.gfbs.auralis.api;

public enum AuralisSoundEvent {
    /** Fired when a sound starts playing. */
    PLAY,
    /** Fired when a sound pauses. */
    PAUSE,
    /** Fired when a sound stops playing naturally. */
    STOP,
    /** Fired when a sound is forcibly stopped. */
    FORCE_STOP,
    /** Fired when a sound is bound to a source. */
    BIND,
    /** Fired when a sound is unbound from a source. */
    UNBIND,
    /** Fired when a sound is evicted due to low priority. */
    EVICTED
}