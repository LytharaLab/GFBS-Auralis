# GFBS: Auralis features

[Back to README](README.md)

## 2.3.2 amplification and extended pitch parameters

Bus and instance gains above `1` now reach OpenAL's supported amplification range instead of hitting the default per-source unity ceiling. A bus volume of `2` (200%) doubles the linear amplitude of an otherwise unchanged sound, approximately +6 dB, until the device/output limits are reached. Fresh and recycled sources receive the detected maximum gain on Auralis' own OpenAL context; Minecraft's vanilla sound context is unaffected.

Pitch parameters now accept `0.01`–`32` through the local API, commands, Tween targets, and synchronization. Speed remains `0.01`–`8`; the logical clock and submitted OpenAL playback multiplier use `clamp(pitch * speed, 0.01, 32)`. This is a parameter limit, **not a guarantee of 32-times audible playback**: the driver's resampler may impose a lower stepping limit. For example, the bundled OpenAL Soft 1.21.1 measured approximately 10-times playback for 48 kHz PCM on a 48 kHz output even while its `AL_PITCH` property was `32`. Auralis does not add software resampling; at that driver limit, audible playback can lag behind the logical timeline.

Amplification requires `AL_SOFT_gain_clamp_ex` and a device gain limit above unity. Unsupported devices retain their valid unity ceiling and emit a startup warning. See [2.3.2 release notes](docs/RELEASE_NOTES_2.3.2.md) for verification and compatibility details.

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

