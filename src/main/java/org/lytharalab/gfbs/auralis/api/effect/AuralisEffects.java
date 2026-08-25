package org.lytharalab.gfbs.auralis.api.effect;

/** Factory helpers and built-in registry bootstrap for the complete EFX set. */
public final class AuralisEffects {
    private static final String PREFIX = "gfbs_auralis:";
    public static final String REVERB = PREFIX + "reverb";
    public static final String EAX_REVERB = PREFIX + "eax_reverb";
    public static final String CHORUS = PREFIX + "chorus";
    public static final String DISTORTION = PREFIX + "distortion";
    public static final String ECHO = PREFIX + "echo";
    public static final String FLANGER = PREFIX + "flanger";
    public static final String FREQUENCY_SHIFTER = PREFIX + "frequency_shifter";
    public static final String VOCAL_MORPHER = PREFIX + "vocal_morpher";
    public static final String PITCH_SHIFTER = PREFIX + "pitch_shifter";
    public static final String RING_MODULATOR = PREFIX + "ring_modulator";
    public static final String AUTOWAH = PREFIX + "autowah";
    public static final String COMPRESSOR = PREFIX + "compressor";
    public static final String EQUALIZER = PREFIX + "equalizer";
    public static final String LOW_PASS = PREFIX + "low_pass";
    public static final String HIGH_PASS = PREFIX + "high_pass";
    public static final String BAND_PASS = PREFIX + "band_pass";

    private AuralisEffects() {
    }

    public static EfxEffect reverb(String id) { return new EfxEffect(id, EfxEffectType.REVERB); }
    public static EfxEffect eaxReverb(String id) { return new EfxEffect(id, EfxEffectType.EAX_REVERB); }
    public static EfxEffect chorus(String id) { return new EfxEffect(id, EfxEffectType.CHORUS); }
    public static EfxEffect distortion(String id) { return new EfxEffect(id, EfxEffectType.DISTORTION); }
    public static EfxEffect echo(String id) { return new EfxEffect(id, EfxEffectType.ECHO); }
    public static EfxEffect flanger(String id) { return new EfxEffect(id, EfxEffectType.FLANGER); }
    public static EfxEffect frequencyShifter(String id) { return new EfxEffect(id, EfxEffectType.FREQUENCY_SHIFTER); }
    public static EfxEffect vocalMorpher(String id) { return new EfxEffect(id, EfxEffectType.VOCAL_MORPHER); }
    public static EfxEffect pitchShifter(String id) { return new EfxEffect(id, EfxEffectType.PITCH_SHIFTER); }
    public static EfxEffect ringModulator(String id) { return new EfxEffect(id, EfxEffectType.RING_MODULATOR); }
    public static EfxEffect autowah(String id) { return new EfxEffect(id, EfxEffectType.AUTOWAH); }
    public static EfxEffect compressor(String id) { return new EfxEffect(id, EfxEffectType.COMPRESSOR); }
    public static EfxEffect equalizer(String id) { return new EfxEffect(id, EfxEffectType.EQUALIZER); }

    public static EfxDirectFilterEffect lowPass(String id) {
        return new EfxDirectFilterEffect(id, new EfxFilter(id + "/filter", EfxFilterType.LOW_PASS));
    }

    public static EfxDirectFilterEffect highPass(String id) {
        return new EfxDirectFilterEffect(id, new EfxFilter(id + "/filter", EfxFilterType.HIGH_PASS));
    }

    public static EfxDirectFilterEffect bandPass(String id) {
        return new EfxDirectFilterEffect(id, new EfxFilter(id + "/filter", EfxFilterType.BAND_PASS));
    }

    public static void registerBuiltIns(AuralisEffectRegistry registry) {
        registry.register(REVERB, AuralisEffects::reverb);
        registry.register(EAX_REVERB, AuralisEffects::eaxReverb);
        registry.register(CHORUS, AuralisEffects::chorus);
        registry.register(DISTORTION, AuralisEffects::distortion);
        registry.register(ECHO, AuralisEffects::echo);
        registry.register(FLANGER, AuralisEffects::flanger);
        registry.register(FREQUENCY_SHIFTER, AuralisEffects::frequencyShifter);
        registry.register(VOCAL_MORPHER, AuralisEffects::vocalMorpher);
        registry.register(PITCH_SHIFTER, AuralisEffects::pitchShifter);
        registry.register(RING_MODULATOR, AuralisEffects::ringModulator);
        registry.register(AUTOWAH, AuralisEffects::autowah);
        registry.register(COMPRESSOR, AuralisEffects::compressor);
        registry.register(EQUALIZER, AuralisEffects::equalizer);
        registry.register(LOW_PASS, AuralisEffects::lowPass);
        registry.register(HIGH_PASS, AuralisEffects::highPass);
        registry.register(BAND_PASS, AuralisEffects::bandPass);
    }
}
