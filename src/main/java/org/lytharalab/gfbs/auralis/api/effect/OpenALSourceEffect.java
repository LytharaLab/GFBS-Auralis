package org.lytharalab.gfbs.auralis.api.effect;

import org.lytharalab.gfbs.auralis.api.openal.OpenALSourceEffectContext;

/**
 * Deep source-level extension point for third-party mods.
 *
 * <p>Callbacks run on Auralis' OpenAL owner thread with its context current.
 * Implementations may call LWJGL OpenAL functions directly. They never receive
 * the underlying AuralisAL object, device handle, context handle, task queue or
 * lifecycle controls.</p>
 */
public interface OpenALSourceEffect extends AuralisEffect {
    @Override
    default EffectBackend getBackend() {
        return EffectBackend.CUSTOM_OPENAL;
    }

    /** Whether Auralis should reserve the suggested EFX auxiliary-send index. */
    default boolean usesAuxiliarySend() {
        return false;
    }

    /** Apply or refresh this effect for one physical source. */
    void apply(OpenALSourceEffectContext context) throws Exception;

    /** Remove any source binding created by {@link #apply}. */
    default void detach(OpenALSourceEffectContext context) throws Exception {
    }
}
