package org.lytharalab.gfbs.auralis.api;

@FunctionalInterface
public interface AuralisSoundListener {
    void onSoundEvent(AuralisSoundInstance instance, AuralisSoundEvent event);
}