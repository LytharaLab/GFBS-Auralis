package org.lytharalab.gfbs.auralis.api.effect;

import org.lwjgl.openal.EXTEfx;

/** Every filter type defined by the standard OpenAL EFX extension. */
public enum EfxFilterType {
    LOW_PASS(EXTEfx.AL_FILTER_LOWPASS),
    HIGH_PASS(EXTEfx.AL_FILTER_HIGHPASS),
    BAND_PASS(EXTEfx.AL_FILTER_BANDPASS);

    private final int alToken;

    EfxFilterType(int alToken) {
        this.alToken = alToken;
    }

    public int alToken() {
        return alToken;
    }
}
