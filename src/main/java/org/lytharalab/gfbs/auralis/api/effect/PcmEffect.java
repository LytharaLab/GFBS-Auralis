package org.lytharalab.gfbs.auralis.api.effect;

import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;

/**
 * Factory-backed custom PCM effect. Auralis creates one processor per logical
 * voice so stateful DSP never leaks delay lines or envelopes between sounds.
 */
public interface PcmEffect extends AuralisEffect {
    @Override
    default EffectBackend getBackend() {
        return EffectBackend.PCM;
    }

    AudioProcessor createProcessor();

    /**
     * Wet-aware factory hook. Implementations that expose wet/dry blending can
     * override this method; the default preserves the legacy processor contract.
     */
    default AudioProcessor createProcessor(float wet) {
        return createProcessor();
    }
}
