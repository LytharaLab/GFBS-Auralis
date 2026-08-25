package org.lytharalab.gfbs.auralis.api.openal;

/** Context passed to a custom source-level OpenAL effect callback. */
public interface OpenALSourceEffectContext {
    /** OpenAL source id, valid only during the callback. */
    int sourceId();

    /**
     * Suggested auxiliary-send index, or -1 when the effect is not assigned an
     * auxiliary send. Implementations must respect the device limit reported by
     * {@link OpenALAccess#getMaxAuxiliarySends()}.
     */
    int auxiliarySendIndex();

    String busName();

    String effectId();

    OpenALAccess openAL();
}
