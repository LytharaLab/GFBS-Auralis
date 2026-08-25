package org.lytharalab.gfbs.auralis.core;

import net.minecraft.sounds.SoundEvent;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import org.lytharalab.gfbs.auralis.api.IAuralisEngine;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectFactory;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectRegistry;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffects;
import org.lytharalab.gfbs.auralis.api.event.AuralisEvent;
import org.lytharalab.gfbs.auralis.api.event.AuralisEventBus;
import org.lytharalab.gfbs.auralis.api.openal.OpenALAccess;
import org.lytharalab.gfbs.auralis.api.plugin.AuralisPlugin;
import org.lytharalab.gfbs.auralis.api.plugin.AuralisPluginService;
import org.lytharalab.gfbs.auralis.api.plugin.PluginContext;
import org.lytharalab.gfbs.auralis.api.plugin.PluginState;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessorFactory;
import org.lytharalab.gfbs.auralis.core.bus.AuralisBusManager;
import org.lytharalab.gfbs.auralis.core.effect.AuralisEffectRegistryImpl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fault-isolated plugin lifecycle, owned-registration cleanup and extension
 * service implementation for Auralis 2.2.
 */
public class AuralisPluginManager implements AuralisPluginService, PluginContext {
    private record FactoryRegistration(String id, AudioProcessorFactory factory, int priority) {
    }

    private record EventRegistration<T extends AuralisEvent>(Class<T> type, Consumer<T> handler) {
    }

    private record LegacyServices(
            IAuralisEngine engine,
            AudioBusSystem buses,
            AuralisEffectRegistry effects,
            OpenALAccess openAL
    ) {
    }

    /**
     * Compatibility view for the pre-2.2 singleton processor API. Calls are
     * serialized on the delegate and voice disposal never closes the shared
     * plugin-owned object. New plugins should always register a factory.
     */
    private static final class SharedProcessorView implements AudioProcessor {
        private final AudioProcessor delegate;

        private SharedProcessorView(AudioProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public int process(ByteBuffer pcmData, int channels, int sampleRate, int bytesRead) {
            synchronized (delegate) {
                return delegate.process(pcmData, channels, sampleRate, bytesRead);
            }
        }

        @Override public String getId() { return delegate.getId(); }
        @Override public boolean isEnabled() { return delegate.isEnabled(); }
        @Override public int getPriority() { return delegate.getPriority(); }
        @Override public long getRevision() { return delegate.getRevision(); }

        @Override
        public void reset() {
            synchronized (delegate) {
                delegate.reset();
            }
        }

        @Override public void close() { }
    }

    private final Object lifecycleLock = new Object();
    private final IAuralisEngine engine;
    private final AudioBusSystem buses;
    private final AuralisEffectRegistry effects;
    private final OpenALAccess openAL;
    private final AuralisEventBusImpl eventBus = new AuralisEventBusImpl();
    private final Map<String, AuralisPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, OwnedContext> contexts = new LinkedHashMap<>();
    private final Map<String, PluginState> states = new ConcurrentHashMap<>();
    private final List<String> loadOrder = new ArrayList<>();
    private final List<AudioProcessor> legacyGlobalProcessors = new CopyOnWriteArrayList<>();
    private final Map<String, FactoryRegistration> globalProcessorFactories = new ConcurrentHashMap<>();
    private final AtomicLong processorRevision = new AtomicLong(1L);
    private final OwnedContext managerContext;
    private volatile boolean shuttingDown;

    /**
     * Compatibility constructor for integrations that used the pre-2.2 manager
     * as a standalone processor/event container. Engine and OpenAL operations
     * are unavailable from that standalone context.
     */
    @Deprecated
    public AuralisPluginManager() {
        this(createLegacyServices());
    }

    private AuralisPluginManager(LegacyServices services) {
        this(services.engine(), services.buses(), services.effects(), services.openAL());
    }

    public AuralisPluginManager(
            IAuralisEngine engine,
            AudioBusSystem buses,
            AuralisEffectRegistry effects,
            OpenALAccess openAL
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.buses = Objects.requireNonNull(buses, "buses");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.openAL = Objects.requireNonNull(openAL, "openAL");
        this.managerContext = new OwnedContext("legacy:manager");
    }

    /** @deprecated Use {@link #load(AuralisPlugin)}. */
    @Deprecated
    public void loadPlugin(AuralisPlugin plugin) {
        load(plugin);
    }

    @Override
    public boolean load(AuralisPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String id = validateId(plugin.getId());
        OwnedContext context;

        synchronized (lifecycleLock) {
            if (shuttingDown) return false;
            PluginState state = states.get(id);
            if (state == PluginState.ENABLED || state == PluginState.ENABLING
                    || state == PluginState.DISABLING) {
                return false;
            }
            AuralisPlugin existing = plugins.get(id);
            if (existing != null && existing != plugin) {
                throw new IllegalArgumentException("Duplicate Auralis plugin id: " + id);
            }
            Set<String> missing = missingDependencies(plugin);
            if (!missing.isEmpty()) {
                states.put(id, PluginState.FAILED);
                GFBsAuralis.LOGGER.error("Cannot load Auralis plugin {}: missing required plugin(s) {}", id, missing);
                return false;
            }
            plugins.put(id, plugin);
            states.put(id, PluginState.ENABLING);
            context = new OwnedContext(id);
            contexts.put(id, context);
        }

        try {
            GFBsAuralis.LOGGER.info("Loading Auralis plugin {} v{}", id, plugin.getVersion());
            plugin.onEnable(context);
            synchronized (lifecycleLock) {
                if (shuttingDown) throw new IllegalStateException("Auralis is shutting down");
                states.put(id, PluginState.ENABLED);
                loadOrder.remove(id);
                loadOrder.add(id);
            }
            GFBsAuralis.LOGGER.info("Enabled Auralis plugin {} v{}", id, plugin.getVersion());
            return true;
        } catch (Throwable failure) {
            context.cleanup();
            synchronized (lifecycleLock) {
                contexts.remove(id);
                states.put(id, PluginState.FAILED);
            }
            GFBsAuralis.LOGGER.error("Failed to enable Auralis plugin {}", id, failure);
            return false;
        }
    }

    @Override
    public int loadAll(Collection<? extends AuralisPlugin> discovered) {
        if (discovered == null || discovered.isEmpty()) return 0;
        List<AuralisPlugin> remaining = new ArrayList<>(discovered);
        if (remaining.removeIf(Objects::isNull)) {
            GFBsAuralis.LOGGER.error("Ignored null entry while loading Auralis plugins");
        }
        if (remaining.isEmpty()) return 0;
        remaining.sort(Comparator
                .comparingInt(AuralisPluginManager::safeLoadPriority)
                .thenComparing(plugin -> plugin.getClass().getName()));

        int loaded = 0;
        boolean progress;
        do {
            progress = false;
            var iterator = remaining.iterator();
            while (iterator.hasNext()) {
                AuralisPlugin plugin = iterator.next();
                try {
                    String id = validateId(plugin.getId());
                    if (states.get(id) == PluginState.ENABLED) {
                        iterator.remove();
                        progress = true;
                        continue;
                    }
                    if (!missingDependencies(plugin).isEmpty()) continue;
                    if (load(plugin)) loaded++;
                    iterator.remove();
                    progress = true;
                } catch (Throwable failure) {
                    String fallbackId = plugin.getClass().getName();
                    try {
                        String id = validateId(plugin.getId());
                        states.put(id, PluginState.FAILED);
                        fallbackId = id;
                    } catch (Throwable ignored) {
                    }
                    GFBsAuralis.LOGGER.error("Failed to load Auralis plugin {}", fallbackId, failure);
                    iterator.remove();
                    progress = true;
                }
            }
        } while (progress && !remaining.isEmpty());

        for (AuralisPlugin plugin : remaining) {
            try {
                String id = validateId(plugin.getId());
                states.put(id, PluginState.FAILED);
                GFBsAuralis.LOGGER.error(
                        "Cannot resolve dependencies for Auralis plugin {}: {}",
                        id,
                        plugin.getRequiredPlugins()
                );
            } catch (Throwable failure) {
                GFBsAuralis.LOGGER.error(
                        "Cannot inspect unresolved Auralis plugin {}",
                        plugin.getClass().getName(),
                        failure
                );
            }
        }
        return loaded;
    }

    @Override
    public int discoverAndLoad(ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        List<AuralisPlugin> discovered = new ArrayList<>();
        try {
            ServiceLoader<AuralisPlugin> services = ServiceLoader.load(AuralisPlugin.class, loader);
            var iterator = services.iterator();
            while (true) {
                try {
                    if (!iterator.hasNext()) break;
                    AuralisPlugin plugin = iterator.next();
                    states.putIfAbsent(validateId(plugin.getId()), PluginState.DISCOVERED);
                    discovered.add(plugin);
                } catch (ServiceConfigurationError serviceFailure) {
                    GFBsAuralis.LOGGER.error("Failed to discover an Auralis service plugin; continuing discovery", serviceFailure);
                }
            }
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.error("Auralis plugin discovery failed", failure);
        }
        return loadAll(discovered);
    }

    @Override
    public boolean unload(String pluginId) {
        String id = validateId(pluginId);
        List<String> dependents;
        synchronized (lifecycleLock) {
            dependents = loadOrder.stream()
                    .filter(candidate -> !candidate.equals(id))
                    .filter(candidate -> requiresPlugin(plugins.get(candidate), id))
                    .toList();
        }
        for (int index = dependents.size() - 1; index >= 0; index--) {
            unload(dependents.get(index));
        }

        AuralisPlugin plugin;
        OwnedContext context;
        boolean enabled;
        synchronized (lifecycleLock) {
            PluginState state = states.get(id);
            if (state != PluginState.ENABLED && state != PluginState.FAILED) return false;
            enabled = state == PluginState.ENABLED;
            plugin = plugins.get(id);
            context = contexts.get(id);
            states.put(id, PluginState.DISABLING);
        }

        Throwable failure = null;
        if (enabled && plugin != null) {
            try {
                plugin.onDisable();
            } catch (Throwable disableFailure) {
                failure = disableFailure;
            }
        }
        if (context != null) context.cleanup();

        synchronized (lifecycleLock) {
            contexts.remove(id);
            loadOrder.remove(id);
            if (plugin != null) plugins.remove(id, plugin);
            states.put(id, failure == null ? PluginState.DISABLED : PluginState.FAILED);
        }
        if (failure != null) {
            GFBsAuralis.LOGGER.error("Auralis plugin {} failed while disabling", id, failure);
        } else {
            GFBsAuralis.LOGGER.info("Disabled Auralis plugin {}", id);
        }
        return true;
    }

    @Override
    public Optional<AuralisPlugin> find(String pluginId) {
        if (pluginId == null) return Optional.empty();
        synchronized (lifecycleLock) {
            return Optional.ofNullable(plugins.get(pluginId.trim().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    @Override
    public Map<String, PluginState> states() {
        return Map.copyOf(states);
    }

    @Override public void registerGlobalProcessor(AudioProcessor processor) { managerContext.registerGlobalProcessor(processor); }
    @Override public void unregisterGlobalProcessor(AudioProcessor processor) { managerContext.unregisterGlobalProcessor(processor); }
    @Override public void registerGlobalProcessorFactory(String id, AudioProcessorFactory factory) {
        managerContext.registerGlobalProcessorFactory(id, factory);
    }
    @Override public void unregisterGlobalProcessorFactory(String id) { managerContext.unregisterGlobalProcessorFactory(id); }
    @Override public void registerEffectType(String typeId, AuralisEffectFactory factory) {
        managerContext.registerEffectType(typeId, factory);
    }
    @Override public void unregisterEffectType(String typeId) { managerContext.unregisterEffectType(typeId); }
    @Override public AuralisEventBus getEventBus() { return managerContext.getEventBus(); }
    @Override public IAuralisEngine engine() { return engine; }
    @Override public AudioBusSystem buses() { return buses; }
    @Override public AuralisEffectRegistry effects() { return effects; }
    @Override public OpenALAccess openAL() { return openAL; }
    @Override public String pluginId() { return "legacy:manager"; }

    /** Legacy singleton registrations only; factory products are per voice. */
    @Deprecated
    public List<AudioProcessor> getGlobalProcessors() {
        return List.copyOf(legacyGlobalProcessors);
    }

    /** Legacy registrations plus fresh per-voice factory products. */
    public List<AudioProcessor> createGlobalProcessors() {
        List<AudioProcessor> result = new ArrayList<>();
        for (AudioProcessor processor : legacyGlobalProcessors) {
            result.add(new SharedProcessorView(processor));
        }
        List<FactoryRegistration> factories = new ArrayList<>(globalProcessorFactories.values());
        factories.sort(Comparator.comparingInt(FactoryRegistration::priority).thenComparing(FactoryRegistration::id));
        for (FactoryRegistration registration : factories) {
            try {
                AudioProcessor processor = Objects.requireNonNull(
                        registration.factory().create(),
                        "Global processor factory returned null: " + registration.id()
                );
                result.add(processor);
            } catch (Throwable failure) {
                GFBsAuralis.LOGGER.error("Failed to create global audio processor {}", registration.id(), failure);
            }
        }
        result.sort(Comparator.comparingInt(AuralisPluginManager::safeProcessorPriority));
        return result;
    }

    public long getProcessorRevision() {
        return processorRevision.get();
    }

    public void shutdown() {
        List<String> reverse;
        synchronized (lifecycleLock) {
            if (shuttingDown) return;
            shuttingDown = true;
            reverse = new ArrayList<>(loadOrder);
        }
        java.util.Collections.reverse(reverse);
        for (String id : reverse) unload(id);

        legacyGlobalProcessors.clear();
        globalProcessorFactories.clear();
        managerContext.cleanup();
        processorRevision.incrementAndGet();
        eventBus.clear();
    }

    private Set<String> missingDependencies(AuralisPlugin plugin) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> required = Objects.requireNonNull(
                plugin.getRequiredPlugins(),
                "Plugin returned null required-plugin set: " + plugin.getId()
        );
        for (String dependency : required) {
            String id = validateId(dependency);
            if (states.get(id) != PluginState.ENABLED) missing.add(id);
        }
        return missing;
    }

    private static boolean requiresPlugin(AuralisPlugin plugin, String dependencyId) {
        if (plugin == null) return false;
        try {
            for (String required : plugin.getRequiredPlugins()) {
                if (validateId(required).equals(dependencyId)) return true;
            }
        } catch (Throwable failure) {
            GFBsAuralis.LOGGER.warn(
                    "Unable to inspect dependencies while unloading {}",
                    plugin.getClass().getName(),
                    failure
            );
        }
        return false;
    }

    private static int safeLoadPriority(AuralisPlugin plugin) {
        try {
            return plugin.getLoadPriority();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeProcessorPriority(AudioProcessor processor) {
        try {
            return processor.getPriority();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static LegacyServices createLegacyServices() {
        AuralisBusManager buses = new AuralisBusManager();
        AuralisEffectRegistryImpl effects = new AuralisEffectRegistryImpl();
        AuralisEffects.registerBuiltIns(effects);
        OpenALAccess openAL = new OpenALAccess() {
            @Override public boolean isAvailable() { return false; }
            @Override public boolean isOnAudioThread() { return false; }
            @Override public boolean isEfxSupported() { return false; }
            @Override public int getMaxAuxiliarySends() { return 0; }
            @Override public void execute(Runnable operation) {
                throw new UnsupportedOperationException("Standalone AuralisPluginManager has no OpenAL context");
            }
            @Override public <T> T call(java.util.concurrent.Callable<T> operation) {
                throw new UnsupportedOperationException("Standalone AuralisPluginManager has no OpenAL context");
            }
            @Override public <T> java.util.concurrent.CompletableFuture<T> submit(
                    java.util.concurrent.Callable<T> operation
            ) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Standalone AuralisPluginManager has no OpenAL context")
                );
            }
        };
        IAuralisEngine engine = new IAuralisEngine() {
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("Standalone AuralisPluginManager has no audio engine");
            }
            @Override public AuralisSoundInstance create(SoundEvent soundEvent) { throw unavailable(); }
            @Override public AuralisSoundInstance createStreamed(SoundEvent soundEvent) { throw unavailable(); }
            @Override public java.util.concurrent.CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent) {
                return java.util.concurrent.CompletableFuture.failedFuture(unavailable());
            }
            @Override public java.util.concurrent.CompletableFuture<AuralisSoundInstance> createStreamedAsync(SoundEvent soundEvent) {
                return java.util.concurrent.CompletableFuture.failedFuture(unavailable());
            }
            @Override public void bind(AuralisSoundInstance instance) { throw unavailable(); }
            @Override public void unbind(AuralisSoundInstance instance) { throw unavailable(); }
            @Override public void tick() { }
            @Override public AudioBusSystem buses() { return buses; }
            @Override public AuralisEffectRegistry effects() { return effects; }
            @Override public OpenALAccess openAL() { return openAL; }
            @Override public void shutdown() { }
        };
        return new LegacyServices(engine, buses, effects, openAL);
    }

    private void registerLegacyProcessor(AudioProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        if (!legacyGlobalProcessors.contains(processor)) {
            legacyGlobalProcessors.add(processor);
            legacyGlobalProcessors.sort(Comparator.comparingInt(AuralisPluginManager::safeProcessorPriority));
            processorRevision.incrementAndGet();
        }
    }

    private void unregisterLegacyProcessor(AudioProcessor processor) {
        if (legacyGlobalProcessors.remove(processor)) processorRevision.incrementAndGet();
    }

    private static String validateId(String id) {
        String value = Objects.requireNonNull(id, "pluginId").trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Plugin id must be namespaced (namespace:path): " + id);
        }
        return value;
    }

    private final class OwnedContext implements PluginContext {
        private final String pluginId;
        private final List<AudioProcessor> processors = new ArrayList<>();
        private final Set<String> processorFactoryIds = new LinkedHashSet<>();
        private final Set<String> effectTypeIds = new LinkedHashSet<>();
        private final List<EventRegistration<?>> events = new ArrayList<>();
        private final AuralisEventBus ownedEventBus = new OwnedEventBus();
        private boolean cleaned;

        private OwnedContext(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public synchronized void registerGlobalProcessor(AudioProcessor processor) {
            requireActive();
            registerLegacyProcessor(processor);
            processors.add(processor);
        }

        @Override
        public synchronized void unregisterGlobalProcessor(AudioProcessor processor) {
            unregisterLegacyProcessor(processor);
            processors.remove(processor);
        }

        @Override
        public synchronized void registerGlobalProcessorFactory(String id, AudioProcessorFactory factory) {
            requireActive();
            String registrationId = validateOwnedId(id);
            FactoryRegistration registration = new FactoryRegistration(registrationId, Objects.requireNonNull(factory, "factory"), 0);
            FactoryRegistration existing = globalProcessorFactories.putIfAbsent(registrationId, registration);
            if (existing != null && existing.factory() != factory) {
                throw new IllegalArgumentException("Global processor factory already registered: " + registrationId);
            }
            processorFactoryIds.add(registrationId);
            if (existing == null) processorRevision.incrementAndGet();
        }

        @Override
        public synchronized void unregisterGlobalProcessorFactory(String id) {
            String registrationId = validateOwnedId(id);
            if (processorFactoryIds.remove(registrationId) && globalProcessorFactories.remove(registrationId) != null) {
                processorRevision.incrementAndGet();
            }
        }

        @Override
        public synchronized void registerEffectType(String typeId, AuralisEffectFactory factory) {
            requireActive();
            String id = validateOwnedId(typeId);
            effects.register(id, factory);
            effectTypeIds.add(id);
        }

        @Override
        public synchronized void unregisterEffectType(String typeId) {
            String id = validateOwnedId(typeId);
            if (effectTypeIds.remove(id)) effects.unregister(id);
        }

        @Override public AuralisEventBus getEventBus() { return ownedEventBus; }
        @Override public IAuralisEngine engine() { return engine; }
        @Override public AudioBusSystem buses() { return buses; }
        @Override public AuralisEffectRegistry effects() { return effects; }
        @Override public OpenALAccess openAL() { return openAL; }
        @Override public String pluginId() { return pluginId; }

        private synchronized void cleanup() {
            if (cleaned) return;
            cleaned = true;
            for (EventRegistration<?> event : new ArrayList<>(events)) unregisterEvent(event);
            events.clear();
            for (AudioProcessor processor : new ArrayList<>(processors)) unregisterLegacyProcessor(processor);
            processors.clear();
            for (String id : processorFactoryIds) globalProcessorFactories.remove(id);
            if (!processorFactoryIds.isEmpty()) processorRevision.incrementAndGet();
            processorFactoryIds.clear();
            for (String id : effectTypeIds) effects.unregister(id);
            effectTypeIds.clear();
        }

        private String validateOwnedId(String raw) {
            String id = Objects.requireNonNull(raw, "id").trim().toLowerCase(java.util.Locale.ROOT);
            if (!id.contains(":")) id = pluginId.substring(0, pluginId.indexOf(':')) + ":" + id;
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid namespaced extension id: " + raw);
            }
            return id;
        }

        private void requireActive() {
            if (cleaned) throw new IllegalStateException("Plugin context is no longer active: " + pluginId);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void unregisterEvent(EventRegistration<?> registration) {
            eventBus.unregister((Class) registration.type(), (Consumer) registration.handler());
        }

        private final class OwnedEventBus implements AuralisEventBus {
            @Override
            public synchronized <T extends AuralisEvent> void register(Class<T> eventClass, Consumer<T> handler) {
                requireActive();
                eventBus.register(eventClass, handler);
                events.add(new EventRegistration<>(eventClass, handler));
            }

            @Override
            public synchronized <T extends AuralisEvent> void unregister(Class<T> eventClass, Consumer<T> handler) {
                eventBus.unregister(eventClass, handler);
                events.removeIf(registration -> registration.type() == eventClass && registration.handler() == handler);
            }

            @Override
            public void post(AuralisEvent event) {
                eventBus.post(event);
            }
        }
    }
}
