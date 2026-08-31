# GFBS: Auralis

An instance-based spatial audio runtime for Minecraft Forge 1.20.1.

GFBS: Auralis runs an independent OpenAL playback layer alongside Minecraft's vanilla sound engine. It gives mods, maps, command systems, and scripted experiences direct control over persistent sound instances, world-space positioning, streamed playback, attenuation, source priority, runtime parameter changes, and server-to-client orchestration.

---
#### Team maintaining this project: [GFBS Mod Series Maintainers](https://github.com/orgs/LytharaLab/teams/gfbs-mod-series-maintainers)
#### 维护此项目的团队: [GFBS Mod Series Maintainers](https://github.com/orgs/LytharaLab/teams/gfbs-mod-series-maintainers)
---

## Repository

- [Source code](https://github.com/LytharaLab/GFBS-Auralis)
- [Releases](https://github.com/LytharaLab/GFBS-Auralis/releases)
- [Issue tracker](https://github.com/LytharaLab/GFBS-Auralis/issues)
- [Pull requests](https://github.com/LytharaLab/GFBS-Auralis/pulls)

## Status and compatibility

| Component | Version |
| --- | --- |
| GFBS: Auralis | `2.3.1` |
| Minecraft | `1.20.1` |
| Minecraft Forge | `47.4.13` |
| Java | `17` |
| Mod ID | `gfbs_auralis` |

The OpenAL engine and all audio-device operations run on the physical client. Install the mod on the server as well when using its commands, packets, synchronized state, entity binding, or block binding.

## 2.3.1 buffered playback start correctness

Auralis 2.3.1 guarantees that a newly started, immediately materialized non-streamed sound begins at sample zero. Scheduler delay is no longer converted into an `AL_SEC_OFFSET` seek before the first `alSourcePlay`, so short transients and leading audio frames are preserved even when asynchronous creation completes just after a client tick.

The fix is scoped to the first physical scheduling opportunity. Voices that actually remain virtual because they are inaudible, below priority, over the Source budget, or temporarily unable to allocate a Source continue advancing their logical timelines and later materialize at the correct virtual playback cursor. Stop/replay generations are validated so a delayed scheduler pass cannot rebase a newer playback cycle.

## 2.3.0 custom PCM data sources

Auralis 2.3.0 makes streaming input a first-class plugin extension. Plugins can register namespaced source factories, produce PCM dynamically, expose seekable timeline media, or feed live network/generated PCM through a bounded non-blocking push source. Temporary live underflow is distinct from end-of-stream, so playback resumes when new data arrives instead of terminating the voice.

The built-in OGG stream now travels through the same source contract as third-party sources. Source reads, seeks, and cleanup are serialized on the OpenAL owner thread; plugin unload automatically unregisters owned factories while already-created voices retain and close their source instances safely.

See [Custom audio data sources](docs/CUSTOM_AUDIO_DATA_SOURCES_2.3.md) for contracts, lifecycle details, and examples.

## 2.2.0 buses, effects, and plugins

Auralis 2.2.0 adds a Godot-style hierarchical audio-bus graph, a complete OpenAL EFX layer, factory-backed custom PCM effects, and a lifecycle-managed extension system.

- Every voice routes to `Master` or a named child bus. Buses can route through any cycle-checked parent depth.
- Volume, mute, solo, local effect bypass, reparenting, removal, immutable route inspection, and real-time server control are supported.
- Effects are ordered from the voice's child bus toward `Master`. Custom PCM effects execute as a serial DSP chain in that order.
- All 13 standard OpenAL EFX 1.0 effects and all three standard filters are exposed with typed, range-clamped parameters.
- Native effect objects, auxiliary slots, and filters are shared across routed sources; unchanged bus snapshots perform no EFX rebuild work.
- Device EFX support and the actual auxiliary-send limit are detected at runtime. Unsupported or rejected effects are bypassed without disabling bus gain/routing or custom PCM processing.
- Plugins can register effect factories, per-voice processor factories, event listeners, and pre-initialization entry points. Dependencies, reverse-order unload, owned-registration cleanup, and callback fault isolation are built in.
- Plugins receive controlled execution on Auralis' active OpenAL thread, but never receive the `AuralisAL` object, its device/context handles, queue, or lifecycle controls.
- Network protocol `3` adds bounded client-bound bus creation, routing, volume, mute, solo, bypass, and per-instance bus assignment.

See [Audio buses and effects](docs/AUDIO_BUSES_AND_EFFECTS.md) and [Plugin API 2.2](docs/PLUGIN_API_2.2.md) for the complete API contract and examples.

## 2.1.1 logical voice virtualization

Auralis 2.1.1 separates a **logical sound instance** from a scarce physical OpenAL Source. This allows large scenes (including 1000+ active instances) to keep correct playback state while only audible/high-priority voices consume real Sources.

- Every playing instance advances an authoritative logical playback cursor, even while virtual.
- Distant/low-contribution voices are virtualized instead of destroyed or stalled waiting for a Source.
- When a listener approaches, static and streamed voices materialize at the current logical time instead of restarting from 0.
- Source stealing now means physical-to-virtual demotion; the logical instance continues to play.
- Streamed voices create OpenAL streaming buffers lazily only while materialized.
- `isPlaying()` reports logical playback, while `isBound()` reports whether a physical OpenAL Source is currently attached.
- Voice materialization/virtualization thresholds are configurable to prevent source churn near the audible boundary.
- Recycled Sources are reset to zero gain before reuse, and distance gain is never temporarily replaced by raw volume during parameter updates; this removes the distant-loop "sound flash" window while the listener moves.

## Features

- Independent OpenAL playback without routing custom instances through Minecraft's vanilla `SoundEngine`.
- Persistent logical sound instances with play, pause, stop, looping, volume, pitch, speed, and priority controls.
- Logical voice virtualization: thousands of instances can keep correct playback state while only audible/high-priority voices consume OpenAL sources.
- Virtual-to-physical resume at the correct playback cursor for both static and streamed OGG audio.
- Listener-relative sounds and world-space 3D sounds with configurable minimum and maximum distances.
- A configurable attenuation curve and per-tick volume smoothing.
- Static-buffer playback and chunked streamed OGG Vorbis playback.
- Asynchronous sound creation and decoded-buffer caching.
- An OpenAL source pool with device-aware limits, vanilla source reservation, and non-destructive physical-voice recycling.
- Audibility hysteresis and zero-gain source reset guards that prevent distant looping sounds from briefly flashing audible during listener movement/source reuse.
- Optional HRTF initialization when supported by the active audio device.
- Server-to-client control through commands and the `AuralisServerApi`.
- Runtime Tween transitions for volume, pitch, speed, position, and attenuation distances.
- Sound instances that can follow entities or block positions.
- Per-instance and global PCM processor hooks.
- Hierarchical audio buses with volume, mute, solo, effect bypass, and live routing.
- Complete OpenAL EFX effects/filters plus third-party PCM and source-level effects.
- Dependency-aware plugin lifecycle, owned registrations, event bus, and controlled OpenAL access.
- Client cleanup on logout, shutdown, resource release, and engine termination.

## Installation

1. Install Minecraft Forge for Minecraft `1.20.1`.
2. Download a GFBS: Auralis JAR from [GitHub Releases](https://github.com/LytharaLab/GFBS-Auralis/releases), or build the project from source.
3. Place the JAR in the `mods` directory of every required client.
4. Install the same JAR on the server when server commands or synchronized control are required.

GFBS: Auralis uses registered Minecraft `SoundEvent` resources. Audio files and `sounds.json` entries remain owned by the resource pack or mod that defines them.

## Building from source

Clone the official repository and run:

```bash
git clone https://github.com/LytharaLab/GFBS-Auralis.git
cd GFBS-Auralis
./gradlew build
```

On Windows PowerShell:

```powershell
git clone https://github.com/LytharaLab/GFBS-Auralis.git
Set-Location GFBS-Auralis
.\gradlew.bat build
```

The built JAR is written to `build/libs/`.

Start development environments with:

```bash
./gradlew runClient
./gradlew runServer
```

## Java API quick start

Create and configure an instance from client-side code:

```java
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;

SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(
        new ResourceLocation("example", "reactor_hum")
);

if (sound == null) {
    throw new IllegalStateException("Missing sound event: example:reactor_hum");
}

AuralisSoundInstance instance = AuralisApi.create(sound)
        .setPosition(new Vec3(128.5, 64.0, -32.5))
        .setStatic(false)
        .setVolume(1.0f)
        .setPitch(1.0f)
        .setSpeed(1.0f)
        .setLooping(true)
        .setPriority(80)
        .setMinDistance(4.0f)
        .setMaxDistance(64.0f);

AuralisSoundInstance.bind(instance);
instance.play();
```

In 2.1.1, `play()` starts the **logical voice**. `bind()` makes the instance eligible for physical playback, but `isBound()` may remain `false` while the voice is virtual. The logical playback clock still advances, `isPlaying()` remains `true`, and the correct playback position is restored when the listener gets close enough or a physical source becomes available.

You can inspect this state with `isVirtual()`, `getPlaybackPositionSeconds()`, and `getDurationSeconds()`.

Stop and release the source when the owning object is removed:

```java
instance.stop();
AuralisSoundInstance.unbind(instance);
```

### Playback completion and instance lifetime

Non-looping instances automatically dispose their audio resources after reaching the natural end by default. Disable that behavior when the same instance must be replayed:

```java
AuralisSoundInstance instance = AuralisApi.create(MY_SOUND)
        .setAutoDisposeOnFinish(false);

AuralisSoundInstance.bind(instance);
instance.play();

// After natural completion the OpenAL source is returned to the pool, but the
// instance, static buffer or stream decoder remain available. This rebinds and
// starts from the beginning.
instance.play();

// Explicitly release the retained resources when finished.
AuralisSoundInstance.unbind(instance);
```

`setAutoDisposeOnFinish(true)` restores one-shot behavior. Source recycling is always enabled and is independent from instance disposal.

Use `AuralisApi.createStreamed(...)` for chunked streamed playback. Asynchronous variants are available through `createAsync(...)` and `createStreamedAsync(...)`.

`setStatic(true)` creates a listener-relative sound without world-distance attenuation. Use `setStatic(false)` with `setPosition(...)` for a positional world sound.

### Bus and native-effect quick start

Create the bus before assigning voices to it:

```java
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffects;
import org.lytharalab.gfbs.auralis.api.effect.EfxParameter;

var buses = AuralisApi.buses();
var ambience = buses.createBus("Ambience");
var reactor = buses.createBus("Reactor", "Ambience")
        .setVolumeDb(-3.0f);

var reverb = AuralisEffects.reverb("example:reactor_reverb")
        .setFloat(EfxParameter.REVERB_DECAY_TIME, 2.8f)
        .setFloat(EfxParameter.REVERB_GAIN, 0.4f)
        .setWet(0.35f);

reactor.addEffect(reverb);
instance.setBus("Reactor");
```

`AudioBusSystem.view(name)` returns the effective gain, audibility, route to `Master`, ordered effects, and compiled revision for diagnostics or management UIs.

## Command control

All commands require permission level `2` and begin with `/gfbs_auralis`.

Play a positional looping sound for every player:

```mcfunction
/gfbs_auralis play minecraft:block.beacon.ambient reactor_hum 1.0 1.0 1.0 false ~ ~ ~ true 80 4.0 64.0 @a
```

The play syntax is:

```text
/gfbs_auralis play <sound> <id> <volume> <pitch> <speed> <static> <position> <looping> <priority> <min-distance> <max-distance> [targets]
```

Use `streamed_play` with the same arguments for streamed playback.

Runtime controls include:

```text
/gfbs_auralis pause <id> [targets]
/gfbs_auralis stop <id> [targets]
/gfbs_auralis regulating volume <id> <value> [targets]
/gfbs_auralis regulating pitch <id> <value> [targets]
/gfbs_auralis regulating speed <id> <value> [targets]
/gfbs_auralis regulating position <id> <position> [targets]
/gfbs_auralis regulating static <id> <value> [targets]
/gfbs_auralis regulating looping <id> <value> [targets]
/gfbs_auralis regulating priority <id> <value> [targets]
/gfbs_auralis regulating min-distance <id> <value> [targets]
/gfbs_auralis regulating max-distance <id> <value> [targets]
/gfbs_auralis regulating bus <id> <bus> [targets]
```

Manage client bus layouts from the server:

```text
/gfbs_auralis bus create <bus> <parent> [targets]
/gfbs_auralis bus remove <bus> [targets]
/gfbs_auralis bus parent <bus> <parent> [targets]
/gfbs_auralis bus volume <bus> <0..16> [targets]
/gfbs_auralis bus mute <bus> <true|false> [targets]
/gfbs_auralis bus solo <bus> <true|false> [targets]
/gfbs_auralis bus bypass-effects <bus> <true|false> [targets]
```

Tween controls support `volume`, `pitch`, `speed`, `position`, `min-distance`, and `max-distance`:

```text
/gfbs_auralis tween <property> <id> <duration> <value> [easing-style] [easing-direction] [targets]
```

A sound may follow an entity or block position:

```text
/gfbs_auralis bind <id> entity <entity> [targets]
/gfbs_auralis bind <id> block <block-position> [targets]
/gfbs_auralis unbind <id> [targets]
```

When the command source is a player, omitted `targets` default to that player. Command blocks and the server console must provide an explicit target selector.

## Configuration

Client audio configuration includes:

- Maximum OpenAL source count, including automatic device-based selection.
- Sources reserved for Minecraft's vanilla audio engine.
- Streamed PCM chunk size and decoded-byte safety limit.
- Distance attenuation exponent.
- Volume smoothing factor.
- Optional HRTF initialization.

Server configuration includes concurrency limits, default volume, remote-sound control, and client-sync policy.

Changing source limits, streaming limits, or HRTF settings may require a client restart because they affect audio-engine initialization.

## Architecture

```text
src/main/java/org/lytharalab/gfbs/auralis/
├── api/         Public engine, bus, effect, OpenAL, plugin, event, and processor APIs
├── command/     Brigadier command registration and dispatch
├── core/        Bus compiler, EFX rack, controlled OpenAL access, events, and plugins
├── event/       Forge synchronization event handlers
├── network/     Client sound, bus, binding, and Tween packets
├── server/      Server-side sound state and synchronization
├── tween/       Tween service, easing, and playback state
├── utils/       OGG Vorbis decoding
└── *.java       OpenAL engine, source pool, buffer cache, and client controller
```

The server describes intended sound state and sends bounded control messages. The client owns decoding, OpenAL sources, listener updates, attenuation, streaming queues, and audio-device calls.

## Scope and compatibility

GFBS: Auralis is intended for orchestrated audio systems that need independently addressable instances. It does not replace Minecraft's entire sound engine, rewrite ordinary vanilla sound playback, or provide audio assets by itself.

Mods that replace or deeply intercept OpenAL, Minecraft audio initialization, or sound-resource loading may require compatibility testing. Source limits are deliberately configurable so Auralis can reserve capacity for vanilla and other audio users.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening an issue or pull request. Participation in the project is governed by [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## License

GFBS: Auralis is available under the [MIT License](LICENSE).

Copyright © 2026 LytharaLab.

Minecraft is a trademark of Microsoft Corporation. This project is not affiliated with or endorsed by Microsoft or Mojang Studios.
