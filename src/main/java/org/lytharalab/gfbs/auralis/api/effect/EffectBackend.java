package org.lytharalab.gfbs.auralis.api.effect;

/**
 * Execution backend used by an Auralis effect.
 */
public enum EffectBackend {
    /** Native OpenAL EFX effect or filter. */
    OPENAL_EFX,
    /** Custom PCM processing supplied by a mod. */
    PCM,
    /** Custom source-level OpenAL integration supplied by a mod. */
    CUSTOM_OPENAL
}
