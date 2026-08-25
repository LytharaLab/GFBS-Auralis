package org.lytharalab.gfbs.auralis.api.bus;

import org.lytharalab.gfbs.auralis.api.effect.AuralisEffect;

import java.util.List;

/** Immutable diagnostic view of one compiled bus route. */
public record AudioBusView(
        String name,
        List<String> routeToMaster,
        float effectiveVolume,
        boolean audible,
        List<AuralisEffect> effects,
        long revision
) {
    public AudioBusView {
        routeToMaster = List.copyOf(routeToMaster);
        effects = List.copyOf(effects);
    }
}
