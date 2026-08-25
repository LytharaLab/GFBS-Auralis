package org.lytharalab.gfbs.auralis.api.bus;

import java.util.List;
import java.util.Optional;

/**
 * Godot-style hierarchical bus layout. Every non-Master bus sends to exactly
 * one parent; parent chains may have arbitrary depth and are cycle checked.
 */
public interface AudioBusSystem {
    String MASTER = "Master";

    AuralisAudioBus master();

    AuralisAudioBus createBus(String name);

    AuralisAudioBus createBus(String name, String parentName);

    Optional<AuralisAudioBus> findBus(String name);

    default AuralisAudioBus requireBus(String name) {
        return findBus(name).orElseThrow(() -> new IllegalArgumentException("Unknown Auralis bus: " + name));
    }

    /**
     * Removes a bus and reparents its direct children to the removed bus's
     * parent. Master cannot be removed.
     */
    boolean removeBus(String name);

    List<AuralisAudioBus> buses();

    /** Immutable compiled route for diagnostics and management UIs. */
    AudioBusView view(String busName);

    /** Resets the layout to an empty Master bus. */
    void reset();
}
