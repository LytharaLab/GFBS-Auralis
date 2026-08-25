package org.lytharalab.gfbs.auralis.api.effect;

import org.lwjgl.openal.EXTEfx;

/** Every effect type defined by the standard OpenAL EFX extension. */
public enum EfxEffectType {
    REVERB(EXTEfx.AL_EFFECT_REVERB),
    EAX_REVERB(EXTEfx.AL_EFFECT_EAXREVERB),
    CHORUS(EXTEfx.AL_EFFECT_CHORUS),
    DISTORTION(EXTEfx.AL_EFFECT_DISTORTION),
    ECHO(EXTEfx.AL_EFFECT_ECHO),
    FLANGER(EXTEfx.AL_EFFECT_FLANGER),
    FREQUENCY_SHIFTER(EXTEfx.AL_EFFECT_FREQUENCY_SHIFTER),
    VOCAL_MORPHER(EXTEfx.AL_EFFECT_VOCAL_MORPHER),
    PITCH_SHIFTER(EXTEfx.AL_EFFECT_PITCH_SHIFTER),
    RING_MODULATOR(EXTEfx.AL_EFFECT_RING_MODULATOR),
    AUTOWAH(EXTEfx.AL_EFFECT_AUTOWAH),
    COMPRESSOR(EXTEfx.AL_EFFECT_COMPRESSOR),
    EQUALIZER(EXTEfx.AL_EFFECT_EQUALIZER);

    private final int alToken;

    EfxEffectType(int alToken) {
        this.alToken = alToken;
    }

    public int alToken() {
        return alToken;
    }
}
