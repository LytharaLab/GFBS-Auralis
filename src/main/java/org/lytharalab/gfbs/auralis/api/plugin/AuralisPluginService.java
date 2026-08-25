package org.lytharalab.gfbs.auralis.api.plugin;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface AuralisPluginService {
    boolean load(AuralisPlugin plugin);

    int loadAll(Collection<? extends AuralisPlugin> plugins);

    int discoverAndLoad(ClassLoader classLoader);

    boolean unload(String pluginId);

    Optional<AuralisPlugin> find(String pluginId);

    Map<String, PluginState> states();
}
