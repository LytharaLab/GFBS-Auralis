package org.lytharalab.gfbs.auralis.api.effect;

import org.lwjgl.openal.EXTEfx;

public enum EfxFilterParameter {
    LOW_PASS_GAIN(EfxFilterType.LOW_PASS, EXTEfx.AL_LOWPASS_GAIN),
    LOW_PASS_GAIN_HF(EfxFilterType.LOW_PASS, EXTEfx.AL_LOWPASS_GAINHF),
    HIGH_PASS_GAIN(EfxFilterType.HIGH_PASS, EXTEfx.AL_HIGHPASS_GAIN),
    HIGH_PASS_GAIN_LF(EfxFilterType.HIGH_PASS, EXTEfx.AL_HIGHPASS_GAINLF),
    BAND_PASS_GAIN(EfxFilterType.BAND_PASS, EXTEfx.AL_BANDPASS_GAIN),
    BAND_PASS_GAIN_LF(EfxFilterType.BAND_PASS, EXTEfx.AL_BANDPASS_GAINLF),
    BAND_PASS_GAIN_HF(EfxFilterType.BAND_PASS, EXTEfx.AL_BANDPASS_GAINHF);

    private final EfxFilterType filterType;
    private final int alToken;

    EfxFilterParameter(EfxFilterType filterType, int alToken) {
        this.filterType = filterType;
        this.alToken = alToken;
    }

    public EfxFilterType filterType() { return filterType; }
    public int alToken() { return alToken; }
}
