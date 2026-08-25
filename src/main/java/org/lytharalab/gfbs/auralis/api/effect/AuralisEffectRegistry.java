package org.lytharalab.gfbs.auralis.api.effect;

import java.util.Map;
import java.util.Optional;

/** Registry of built-in and third-party effect factories. */
public interface AuralisEffectRegistry {
    void register(String typeId, AuralisEffectFactory factory);

    boolean unregister(String typeId);

    Optional<AuralisEffectFactory> find(String typeId);

    default AuralisEffect create(String typeId, String instanceId) {
        return find(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Auralis effect type: " + typeId))
                .create(instanceId);
    }

    Map<String, AuralisEffectFactory> factories();
}
