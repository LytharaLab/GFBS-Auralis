package org.lytharalab.gfbs.auralis.api.source;

import java.nio.ByteBuffer;

/**
 * Plugin-provided PCM producer. All methods are serialized on Auralis' OpenAL
 * owner thread. Implementations must not block while waiting for live data.
 */
public interface AudioDataSource extends AutoCloseable {
    PcmFormat format();

    AudioSourceMode mode();

    /**
     * Append whole PCM frames to {@code target}, advancing its position.
     * Returning DATA without writing bytes, writing partial frames, or exceeding
     * the target limit is a contract violation and terminates this voice.
     */
    AudioReadResult read(ByteBuffer target) throws Exception;

    /** Duration in seconds, or 0 when unknown/live. */
    default double durationSeconds() { return 0.0; }

    /** Seek a TIMELINE source. LIVE sources normally retain the default. */
    default void seekSeconds(double seconds) throws Exception {
        throw new UnsupportedOperationException("This audio source is not seekable");
    }

    default boolean isSeekable() { return false; }

    @Override
    void close() throws Exception;
}
