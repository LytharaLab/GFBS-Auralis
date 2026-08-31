package org.lytharalab.gfbs.auralis.api.source;

/** Immutable PCM format accepted by Auralis custom data sources. */
public record PcmFormat(int channels, int sampleRate, int bitsPerSample) {
    public PcmFormat {
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Auralis PCM sources must be mono or stereo");
        }
        if (sampleRate < 1 || sampleRate > 768_000) {
            throw new IllegalArgumentException("Invalid PCM sample rate: " + sampleRate);
        }
        if (bitsPerSample != 16) {
            throw new IllegalArgumentException("Auralis 2.3 custom sources currently require signed PCM16");
        }
    }

    public int frameSizeBytes() {
        return channels * (bitsPerSample / 8);
    }
}
