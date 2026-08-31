package org.lytharalab.gfbs.auralis.api.source;

/** Defines how a source behaves when its physical OpenAL voice is virtualized. */
public enum AudioSourceMode {
    /** Finite or generated media that can seek to the engine's logical clock. */
    TIMELINE,
    /** A live producer whose unavailable data must be treated as temporary underflow. */
    LIVE
}
