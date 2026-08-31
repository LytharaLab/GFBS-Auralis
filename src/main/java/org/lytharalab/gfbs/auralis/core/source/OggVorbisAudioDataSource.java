package org.lytharalab.gfbs.auralis.core.source;

import org.lytharalab.gfbs.auralis.api.source.AudioDataSource;
import org.lytharalab.gfbs.auralis.api.source.AudioReadResult;
import org.lytharalab.gfbs.auralis.api.source.AudioSourceMode;
import org.lytharalab.gfbs.auralis.api.source.PcmFormat;
import org.lytharalab.gfbs.auralis.utils.OggVorbisDecoder;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Bridges the built-in Vorbis decoder through the same 2.3 source contract used by plugins. */
public final class OggVorbisAudioDataSource implements AudioDataSource {
    private final OggVorbisDecoder.StreamDecoder decoder;
    private final PcmFormat format;

    public OggVorbisAudioDataSource(OggVorbisDecoder.StreamDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.format = new PcmFormat(decoder.getChannels(), decoder.getSampleRate(), 16);
    }

    @Override public PcmFormat format() { return format; }
    @Override public AudioSourceMode mode() { return AudioSourceMode.TIMELINE; }

    @Override
    public AudioReadResult read(ByteBuffer target) {
        return decoder.decodeChunk(target) > 0 ? AudioReadResult.DATA : AudioReadResult.END;
    }

    @Override public double durationSeconds() { return decoder.getDurationSeconds(); }
    @Override public void seekSeconds(double seconds) { decoder.seekSeconds(seconds); }
    @Override public boolean isSeekable() { return true; }
    @Override public void close() { decoder.close(); }
}
