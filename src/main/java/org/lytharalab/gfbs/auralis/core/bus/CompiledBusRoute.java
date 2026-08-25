package org.lytharalab.gfbs.auralis.core.bus;

import org.lytharalab.gfbs.auralis.api.effect.AuralisEffect;
import org.lytharalab.gfbs.auralis.api.effect.PcmEffect;

import java.util.List;

/** Immutable hot-path representation of one bus-to-Master route. */
public record CompiledBusRoute(
        String busName,
        List<String> routeToMaster,
        float gain,
        boolean audible,
        List<AuralisEffect> effects,
        List<PcmEffect> pcmEffects,
        long signature,
        long effectSignature,
        long pcmSignature
) {
    public CompiledBusRoute {
        routeToMaster = List.copyOf(routeToMaster);
        effects = List.copyOf(effects);
        pcmEffects = List.copyOf(pcmEffects);
    }
}
