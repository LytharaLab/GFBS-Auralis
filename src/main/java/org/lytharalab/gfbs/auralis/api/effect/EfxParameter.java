package org.lytharalab.gfbs.auralis.api.effect;

import org.lwjgl.openal.EXTEfx;

/**
 * Typed metadata for every parameter in the OpenAL EFX 1.0 effect set.
 * Values are clamped to the normative EFX ranges before they reach a driver.
 */
public enum EfxParameter {
    REVERB_DENSITY(EfxEffectType.REVERB, EXTEfx.AL_REVERB_DENSITY, 0f, 1f, 1f),
    REVERB_DIFFUSION(EfxEffectType.REVERB, EXTEfx.AL_REVERB_DIFFUSION, 0f, 1f, 1f),
    REVERB_GAIN(EfxEffectType.REVERB, EXTEfx.AL_REVERB_GAIN, 0f, 1f, 0.32f),
    REVERB_GAIN_HF(EfxEffectType.REVERB, EXTEfx.AL_REVERB_GAINHF, 0f, 1f, 0.89f),
    REVERB_DECAY_TIME(EfxEffectType.REVERB, EXTEfx.AL_REVERB_DECAY_TIME, 0.1f, 20f, 1.49f),
    REVERB_DECAY_HF_RATIO(EfxEffectType.REVERB, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.1f, 2f, 0.83f),
    REVERB_REFLECTIONS_GAIN(EfxEffectType.REVERB, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0f, 3.16f, 0.05f),
    REVERB_REFLECTIONS_DELAY(EfxEffectType.REVERB, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, 0f, 0.3f, 0.007f),
    REVERB_LATE_GAIN(EfxEffectType.REVERB, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 0f, 10f, 1.26f),
    REVERB_LATE_DELAY(EfxEffectType.REVERB, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0f, 0.1f, 0.011f),
    REVERB_AIR_ABSORPTION_GAIN_HF(EfxEffectType.REVERB, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, 0.892f, 1f, 0.994f),
    REVERB_ROOM_ROLLOFF(EfxEffectType.REVERB, EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR, 0f, 10f, 0f),
    REVERB_DECAY_HF_LIMIT(EfxEffectType.REVERB, EXTEfx.AL_REVERB_DECAY_HFLIMIT, 0, 1, 1),

    EAX_DENSITY(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DENSITY, 0f, 1f, 1f),
    EAX_DIFFUSION(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DIFFUSION, 0f, 1f, 1f),
    EAX_GAIN(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_GAIN, 0f, 1f, 0.32f),
    EAX_GAIN_HF(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_GAINHF, 0f, 1f, 0.89f),
    EAX_GAIN_LF(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_GAINLF, 0f, 1f, 1f),
    EAX_DECAY_TIME(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DECAY_TIME, 0.1f, 20f, 1.49f),
    EAX_DECAY_HF_RATIO(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 0.1f, 2f, 0.83f),
    EAX_DECAY_LF_RATIO(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, 0.1f, 2f, 1f),
    EAX_REFLECTIONS_GAIN(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 0f, 3.16f, 0.05f),
    EAX_REFLECTIONS_DELAY(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, 0f, 0.3f, 0.007f),
    EAX_REFLECTIONS_PAN(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_REFLECTIONS_PAN),
    EAX_LATE_GAIN(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, 0f, 10f, 1.26f),
    EAX_LATE_DELAY(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, 0f, 0.1f, 0.011f),
    EAX_LATE_PAN(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_LATE_REVERB_PAN),
    EAX_ECHO_TIME(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_ECHO_TIME, 0.075f, 0.25f, 0.25f),
    EAX_ECHO_DEPTH(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, 0f, 1f, 0f),
    EAX_MODULATION_TIME(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_MODULATION_TIME, 0.04f, 4f, 0.25f),
    EAX_MODULATION_DEPTH(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_MODULATION_DEPTH, 0f, 1f, 0f),
    EAX_AIR_ABSORPTION_GAIN_HF(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 0.892f, 1f, 0.994f),
    EAX_HF_REFERENCE(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_HFREFERENCE, 1000f, 20000f, 5000f),
    EAX_LF_REFERENCE(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_LFREFERENCE, 20f, 1000f, 250f),
    EAX_ROOM_ROLLOFF(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0f, 10f, 0f),
    EAX_DECAY_HF_LIMIT(EfxEffectType.EAX_REVERB, EXTEfx.AL_EAXREVERB_DECAY_HFLIMIT, 0, 1, 1),

    CHORUS_WAVEFORM(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_WAVEFORM, 0, 1, 1),
    CHORUS_PHASE(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_PHASE, -180, 180, 90),
    CHORUS_RATE(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_RATE, 0f, 10f, 1.1f),
    CHORUS_DEPTH(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_DEPTH, 0f, 1f, 0.1f),
    CHORUS_FEEDBACK(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_FEEDBACK, -1f, 1f, 0.25f),
    CHORUS_DELAY(EfxEffectType.CHORUS, EXTEfx.AL_CHORUS_DELAY, 0f, 0.016f, 0.016f),

    DISTORTION_EDGE(EfxEffectType.DISTORTION, EXTEfx.AL_DISTORTION_EDGE, 0f, 1f, 0.2f),
    DISTORTION_GAIN(EfxEffectType.DISTORTION, EXTEfx.AL_DISTORTION_GAIN, 0.01f, 1f, 0.05f),
    DISTORTION_LOWPASS_CUTOFF(EfxEffectType.DISTORTION, EXTEfx.AL_DISTORTION_LOWPASS_CUTOFF, 80f, 24000f, 8000f),
    DISTORTION_EQ_CENTER(EfxEffectType.DISTORTION, EXTEfx.AL_DISTORTION_EQCENTER, 80f, 24000f, 3600f),
    DISTORTION_EQ_BANDWIDTH(EfxEffectType.DISTORTION, EXTEfx.AL_DISTORTION_EQBANDWIDTH, 80f, 24000f, 3600f),

    ECHO_DELAY(EfxEffectType.ECHO, EXTEfx.AL_ECHO_DELAY, 0f, 0.207f, 0.1f),
    ECHO_LR_DELAY(EfxEffectType.ECHO, EXTEfx.AL_ECHO_LRDELAY, 0f, 0.404f, 0.1f),
    ECHO_DAMPING(EfxEffectType.ECHO, EXTEfx.AL_ECHO_DAMPING, 0f, 0.99f, 0.5f),
    ECHO_FEEDBACK(EfxEffectType.ECHO, EXTEfx.AL_ECHO_FEEDBACK, 0f, 1f, 0.5f),
    ECHO_SPREAD(EfxEffectType.ECHO, EXTEfx.AL_ECHO_SPREAD, -1f, 1f, -1f),

    FLANGER_WAVEFORM(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_WAVEFORM, 0, 1, 1),
    FLANGER_PHASE(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_PHASE, -180, 180, 0),
    FLANGER_RATE(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_RATE, 0f, 10f, 0.27f),
    FLANGER_DEPTH(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_DEPTH, 0f, 1f, 1f),
    FLANGER_FEEDBACK(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_FEEDBACK, -1f, 1f, -0.5f),
    FLANGER_DELAY(EfxEffectType.FLANGER, EXTEfx.AL_FLANGER_DELAY, 0f, 0.004f, 0.002f),

    FREQUENCY_SHIFTER_FREQUENCY(EfxEffectType.FREQUENCY_SHIFTER, EXTEfx.AL_FREQUENCY_SHIFTER_FREQUENCY, 0f, 24000f, 0f),
    FREQUENCY_SHIFTER_LEFT_DIRECTION(EfxEffectType.FREQUENCY_SHIFTER, EXTEfx.AL_FREQUENCY_SHIFTER_LEFT_DIRECTION, 0, 2, 0),
    FREQUENCY_SHIFTER_RIGHT_DIRECTION(EfxEffectType.FREQUENCY_SHIFTER, EXTEfx.AL_FREQUENCY_SHIFTER_RIGHT_DIRECTION, 0, 2, 0),

    VOCAL_PHONEME_A(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_PHONEMEA, 0, 29, 0),
    VOCAL_PHONEME_A_COARSE(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_PHONEMEA_COARSE_TUNING, -24, 24, 0),
    VOCAL_PHONEME_B(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_PHONEMEB, 0, 29, 10),
    VOCAL_PHONEME_B_COARSE(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_PHONEMEB_COARSE_TUNING, -24, 24, 0),
    VOCAL_WAVEFORM(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_WAVEFORM, 0, 2, 0),
    VOCAL_RATE(EfxEffectType.VOCAL_MORPHER, EXTEfx.AL_VOCMORPHER_RATE, 0f, 10f, 1.41f),

    PITCH_COARSE_TUNE(EfxEffectType.PITCH_SHIFTER, EXTEfx.AL_PITCH_SHIFTER_COARSE_TUNE, -12, 12, 12),
    PITCH_FINE_TUNE(EfxEffectType.PITCH_SHIFTER, EXTEfx.AL_PITCH_SHIFTER_FINE_TUNE, -50, 50, 0),

    RING_FREQUENCY(EfxEffectType.RING_MODULATOR, EXTEfx.AL_RING_MODULATOR_FREQUENCY, 0f, 8000f, 440f),
    RING_HIGHPASS_CUTOFF(EfxEffectType.RING_MODULATOR, EXTEfx.AL_RING_MODULATOR_HIGHPASS_CUTOFF, 0f, 24000f, 800f),
    RING_WAVEFORM(EfxEffectType.RING_MODULATOR, EXTEfx.AL_RING_MODULATOR_WAVEFORM, 0, 2, 0),

    AUTOWAH_ATTACK_TIME(EfxEffectType.AUTOWAH, EXTEfx.AL_AUTOWAH_ATTACK_TIME, 0.0001f, 1f, 0.06f),
    AUTOWAH_RELEASE_TIME(EfxEffectType.AUTOWAH, EXTEfx.AL_AUTOWAH_RELEASE_TIME, 0.0001f, 1f, 0.06f),
    AUTOWAH_RESONANCE(EfxEffectType.AUTOWAH, EXTEfx.AL_AUTOWAH_RESONANCE, 2f, 1000f, 1000f),
    AUTOWAH_PEAK_GAIN(EfxEffectType.AUTOWAH, EXTEfx.AL_AUTOWAH_PEAK_GAIN, 0.00003f, 31621f, 11.22f),

    COMPRESSOR_ON(EfxEffectType.COMPRESSOR, EXTEfx.AL_COMPRESSOR_ONOFF, 0, 1, 1),

    EQ_LOW_GAIN(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_LOW_GAIN, 0.126f, 7.943f, 1f),
    EQ_LOW_CUTOFF(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_LOW_CUTOFF, 50f, 800f, 200f),
    EQ_MID1_GAIN(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID1_GAIN, 0.126f, 7.943f, 1f),
    EQ_MID1_CENTER(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID1_CENTER, 200f, 3000f, 500f),
    EQ_MID1_WIDTH(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID1_WIDTH, 0.01f, 1f, 1f),
    EQ_MID2_GAIN(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID2_GAIN, 0.126f, 7.943f, 1f),
    EQ_MID2_CENTER(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID2_CENTER, 1000f, 8000f, 3000f),
    EQ_MID2_WIDTH(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_MID2_WIDTH, 0.01f, 1f, 1f),
    EQ_HIGH_GAIN(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_HIGH_GAIN, 0.126f, 7.943f, 1f),
    EQ_HIGH_CUTOFF(EfxEffectType.EQUALIZER, EXTEfx.AL_EQUALIZER_HIGH_CUTOFF, 4000f, 16000f, 6000f);

    private final EfxEffectType effectType;
    private final int alToken;
    private final EfxValueKind kind;
    private final float min;
    private final float max;
    private final Object defaultValue;

    EfxParameter(EfxEffectType effectType, int alToken, float min, float max, float defaultValue) {
        this.effectType = effectType;
        this.alToken = alToken;
        this.kind = EfxValueKind.FLOAT;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
    }

    EfxParameter(EfxEffectType effectType, int alToken, int min, int max, int defaultValue) {
        this.effectType = effectType;
        this.alToken = alToken;
        this.kind = EfxValueKind.INTEGER;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
    }

    EfxParameter(EfxEffectType effectType, int alToken) {
        this.effectType = effectType;
        this.alToken = alToken;
        this.kind = EfxValueKind.VECTOR3;
        this.min = -1f;
        this.max = 1f;
        this.defaultValue = new float[] {0f, 0f, 0f};
    }

    public EfxEffectType effectType() { return effectType; }
    public int alToken() { return alToken; }
    public EfxValueKind kind() { return kind; }
    public float min() { return min; }
    public float max() { return max; }

    public Object defaultValue() {
        return defaultValue instanceof float[] vector ? vector.clone() : defaultValue;
    }

    float clampFloat(float value) {
        float finite = Float.isFinite(value) ? value : ((Number) defaultValue).floatValue();
        return Math.max(min, Math.min(max, finite));
    }

    int clampInt(int value) {
        return Math.max((int) min, Math.min((int) max, value));
    }
}
