package org.lytharalab.gfbs.auralis.api.effect;

/** Creates an independently configurable effect instance. */
@FunctionalInterface
public interface AuralisEffectFactory {
    AuralisEffect create(String instanceId);
}
