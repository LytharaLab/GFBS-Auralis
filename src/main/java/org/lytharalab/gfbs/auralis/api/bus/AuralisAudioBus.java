package org.lytharalab.gfbs.auralis.api.bus;

import org.lytharalab.gfbs.auralis.api.effect.AuralisEffect;

import java.util.List;

/** Mutable node in the Auralis audio-bus routing tree. */
public interface AuralisAudioBus {
    String getName();

    /** Parent/send bus name. Master has no parent. */
    String getParentName();

    AuralisAudioBus setParent(String parentName);

    /** Linear volume multiplier. Values above 1 are allowed up to 16. */
    float getVolume();

    AuralisAudioBus setVolume(float volume);

    default float getVolumeDb() {
        float linear = getVolume();
        return linear <= 0.000001f ? -120.0f : (float) (20.0 * Math.log10(linear));
    }

    default AuralisAudioBus setVolumeDb(float decibels) {
        float db = Float.isFinite(decibels) ? Math.max(-120.0f, Math.min(24.0f, decibels)) : -120.0f;
        return setVolume(db <= -120.0f ? 0.0f : (float) Math.pow(10.0, db / 20.0));
    }

    boolean isMuted();

    AuralisAudioBus setMuted(boolean muted);

    boolean isSolo();

    AuralisAudioBus setSolo(boolean solo);

    /** Bypass this bus's effects while retaining parent effects and routing. */
    boolean isEffectsBypassed();

    AuralisAudioBus setEffectsBypassed(boolean bypassed);

    List<AuralisEffect> getEffects();

    AuralisAudioBus addEffect(AuralisEffect effect);

    AuralisAudioBus insertEffect(int index, AuralisEffect effect);

    boolean removeEffect(AuralisEffect effect);

    AuralisEffect removeEffect(int index);

    AuralisAudioBus moveEffect(int fromIndex, int toIndex);

    long getRevision();
}
