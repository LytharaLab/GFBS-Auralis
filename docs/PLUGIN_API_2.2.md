# Plugin API 2.2

## Loading a plugin

Auralis supports both explicit registration and Java `ServiceLoader` discovery. Explicit registration is usually simplest for a Forge mod and is safe before the client engine exists:

```java
// Call during client setup.
AuralisApi.registerPlugin(new ExampleAuralisPlugin());
```

For discovery, implement `AuralisPlugin` and add its binary class name to:

```text
META-INF/services/org.lytharalab.gfbs.auralis.api.plugin.AuralisPlugin
```

```java
public final class ExampleAuralisPlugin implements AuralisPlugin {
    @Override public String getId() { return "example:audio"; }
    @Override public String getName() { return "Example Audio"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public Set<String> getRequiredPlugins() { return Set.of(); }
    @Override public int getLoadPriority() { return 0; }

    @Override
    public void onEnable(PluginContext context) {
        // Register extensions here.
    }

    @Override
    public void onDisable() {
        // Release resources not owned through PluginContext here.
    }
}
```

Plugin and extension IDs are lowercase namespaced IDs such as `example:audio`. Unqualified registration IDs passed through `PluginContext` inherit the namespace from the plugin ID.

## Lifecycle guarantees

- Required plugins must be enabled before a dependent plugin loads.
- Dependencies may cross explicit registration and `ServiceLoader`; queued explicit plugins are retried after discovery.
- Independent plugins use `getLoadPriority()` and then deterministic class-name ordering.
- Missing/cyclic dependencies and `onEnable` failures mark only the affected plugin as failed.
- Registrations made through a plugin's context are rolled back if enable fails.
- Unloading a plugin first unloads enabled dependents, then calls `onDisable` and removes owned registrations.
- Engine shutdown unloads plugins in reverse load order.
- A disabled plugin ID can be loaded again with a replacement instance.
- Event, processor, effect, and lifecycle callback failures are logged and isolated from the OpenAL owner thread and other plugins.

Inspect runtime state through `AuralisApi.plugins().states()` or `find(id)`. Use `load`, `loadAll`, and `unload` for explicit runtime management.
`AuralisApi.unregisterPlugin(id)` also removes a pre-initialization registration so it will not be restored on the next engine start.

## PluginContext services

| Service | Purpose |
| --- | --- |
| `engine()` | Public `IAuralisEngine` operations and voice diagnostics |
| `buses()` | Create, route, inspect, and modify hierarchical buses |
| `effects()` | Create registered effects or inspect effect factories |
| `openAL()` | Controlled work on the active OpenAL context |
| `getEventBus()` | Owned event listener registration and plugin events |
| `registerEffectType(...)` | Register a custom effect factory with automatic unload cleanup |
| `registerGlobalProcessorFactory(...)` | Create a dedicated processor for every logical voice |
| `registerGlobalProcessor(...)` | Compatibility-only singleton processor registration |

Prefer `registerGlobalProcessorFactory`. It avoids state sharing across voices and lets Auralis close each produced processor with its voice. The legacy singleton path is serialized for safety, is not closed by individual voices, and remains owned by the registering plugin.

```java
@Override
public void onEnable(PluginContext context) {
    context.registerEffectType("saturation", SaturationEffect::new);
    context.registerGlobalProcessorFactory(
            "output_limiter",
            OutputLimiterProcessor::new
    );

    context.getEventBus().register(SoundCreatedEvent.class, event -> {
        if (shouldUseRadioBus(event.soundEvent())) {
            event.instance().setBus("Radio");
        }
    });
}
```

Event listeners registered through `PluginContext.getEventBus()` are automatically removed on unload. Dispatch supports base event types: a listener registered for `AuralisEvent` receives implementing events as well.

## Registering and using custom effects

```java
context.registerEffectType("saturation", SaturationEffect::new);

var effect = context.effects().create(
        "example:saturation",
        "example:radio_saturation"
);
context.buses().requireBus("Radio").addEffect(effect);
```

Factories must return a new independently configurable effect for the requested instance ID. See [Audio buses and effects](AUDIO_BUSES_AND_EFFECTS.md) for `PcmEffect` and `OpenALSourceEffect` contracts.

The plugin owns semantic changes it makes to shared engine state. Context cleanup unregisters the factory type, but it does not guess which buses/effect instances a plugin intended to remove. Keep references to created buses/effects and undo those changes in `onDisable` when hot unload is required.

## Controlled OpenAL access

`OpenALAccess` provides synchronous and asynchronous execution with Auralis' context current:

```java
OpenALAccess al = context.openAL();

int vendorOwnedObject = al.call(() -> {
    int filter = EXTEfx.alGenFilters();
    EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
    return filter;
});

al.execute(() -> EXTEfx.alDeleteFilters(vendorOwnedObject));
```

`execute` and `call` block until the operation completes; `submit` returns a `CompletableFuture`. If invoked from the OpenAL owner thread, blocking calls execute inline to avoid deadlock.

The boundary intentionally does **not** expose:

- the internal `AuralisAL` instance;
- ALC device or context handles;
- the internal task queue;
- context/device replacement;
- AuralisAL startup, shutdown, or ownership controls.

Plugins may call LWJGL AL/EFX functions inside the callback. They must not switch the current context, close the device, stop/rebind sources they do not own, perform blocking I/O, or retain core-owned object IDs after their callback/lifecycle ends.

Use `isEfxSupported()` and `getMaxAuxiliarySends()` before allocating EFX resources. All plugin-owned AL objects must be deleted while `OpenALAccess.isAvailable()` is true, normally in `onDisable`.

## Performance rules

- Use native EFX for standard effects; Auralis shares one native runtime per effect identity across all routed physical sources.
- Use one `AudioProcessor` instance per voice for stateful PCM DSP.
- Avoid allocation, locks with external owners, logging per sample/chunk, file I/O, and network I/O in audio callbacks.
- Keep `getRevision()` stable until rendering parameters actually change.
- Keep source-effect `apply` idempotent; it runs on materialization and after route/effect revision changes, not as a general per-frame callback.
- Treat callbacks as real-time work. Auralis isolates exceptions and invalid output, but cannot make an arbitrarily slow third-party DSP implementation real-time safe.
