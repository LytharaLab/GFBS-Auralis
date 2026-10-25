package org.lytharalab.gfbs.auralis.api;

/** Shared numeric contracts for local playback and server synchronization. No client classes are loaded here. */
public final class AuralisPlaybackParameters {
    public static final float MIN_RATE = 0.01f;
    public static final float MAX_PITCH = 32.0f;
    public static final float MAX_SPEED = 8.0f;

    private AuralisPlaybackParameters() {}

    public static float volume(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, value) : 0.0f;
    }

    public static float pitch(float value) {
        return clampRate(value, MAX_PITCH);
    }

    public static float speed(float value) {
        return clampRate(value, MAX_SPEED);
    }

    /** Logical time and the submitted native pitch use the same combined pitch/speed ceiling. */
    public static float playbackRate(float pitch, float speed) {
        return Math.max(MIN_RATE, Math.min(MAX_PITCH, pitch(pitch) * speed(speed)));
    }

    /** Keep finite API values from overflowing before smoothing and distance attenuation. */
    public static float combinedVolume(float volume, float busGain) {
        return (float) Math.min(Float.MAX_VALUE, (double) volume(volume) * volume(busGain));
    }

    private static float clampRate(float value, float maximum) {
        return Float.isFinite(value) ? Math.max(MIN_RATE, Math.min(maximum, value)) : 1.0f;
    }
}
