package org.lytharalab.gfbs.auralis.api.source;

/** Result of one non-blocking PCM source read. */
public enum AudioReadResult {
    /** PCM bytes were appended to the supplied target buffer. */
    DATA,
    /** No data is available yet; the engine should retry on a later audio tick. */
    WAIT,
    /** The source has permanently reached end-of-stream. */
    END
}
