package org.lytharalab.gfbs.auralis.core.bus;

import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;

import java.util.Collection;
import java.util.Map;

/** Immutable snapshot published once per client tick. */
public final class BusMixSnapshot {
    private final long revision;
    private final Map<String, CompiledBusRoute> routes;
    private final CompiledBusRoute master;

    BusMixSnapshot(long revision, Map<String, CompiledBusRoute> routes) {
        this.revision = revision;
        this.routes = Map.copyOf(routes);
        this.master = this.routes.get(AudioBusSystem.MASTER);
    }

    public long revision() { return revision; }

    public CompiledBusRoute route(String name) {
        CompiledBusRoute route = name == null ? null : routes.get(name);
        return route != null ? route : master;
    }

    public Collection<CompiledBusRoute> routes() {
        return routes.values();
    }
}
