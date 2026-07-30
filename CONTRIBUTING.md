# Contributing to GFBS: Auralis

Thank you for considering a contribution to GFBS: Auralis. This guide covers bug reports, feature proposals, code changes, documentation, audio-engine testing, and extension APIs for the [official repository](https://github.com/LytharaLab/GFBS-Auralis).

By participating, you agree to follow the project [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Before you begin

- Search the [existing issues](https://github.com/LytharaLab/GFBS-Auralis/issues) and [pull requests](https://github.com/LytharaLab/GFBS-Auralis/pulls) before opening a duplicate.
- Keep each issue and pull request focused on one defect, feature, or coherent refactor.
- Discuss protocol changes, public API breaks, OpenAL lifecycle changes, new decoder formats, source-pool redesigns, and large command changes before implementing them.
- Do not publish credentials, access tokens, private server addresses, personal file paths, private audio assets, or unrelated internal project material.
- Do not attach audio, models, logs, or other assets that you do not have permission to redistribute.
- Preserve copyright notices, source attribution, and third-party licenses when adapting external work.

## Development environment

The project currently targets:

- Minecraft `1.20.1`
- Minecraft Forge `47.4.13`
- Java `17`
- Gradle through the included wrapper

Clone the repository:

```bash
git clone https://github.com/LytharaLab/GFBS-Auralis.git
cd GFBS-Auralis
```

Import it as a Gradle project in your IDE. Use the included wrapper instead of a separately installed Gradle version.

Build the project:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

Run the complete Gradle verification lifecycle:

```bash
./gradlew check
```

Start the development client or dedicated server when integration testing is required:

```bash
./gradlew runClient
./gradlew runServer
```

## Reporting bugs

Open bug reports in the [issue tracker](https://github.com/LytharaLab/GFBS-Auralis/issues). A useful report should include:

- The GFBS: Auralis version or commit.
- Minecraft, Forge, Java, operating-system, and audio-device information.
- Whether the problem occurs in single-player, an integrated server, a dedicated server, or all environments.
- The relevant client and server configuration values.
- A minimal list of other mods and resource packs required to reproduce the issue.
- The affected `SoundEvent` identifier and whether playback is buffered or streamed.
- Clear reproduction steps.
- Expected and actual behavior.
- Relevant logs or stack traces with private information removed.
- A short recording when the defect is audible but not visible in logs.

For source exhaustion, cut-off audio, stutter, or device failures, also include the configured source count, vanilla reserve, streaming limits, HRTF state, and approximate number of simultaneous instances.

Do not report a security vulnerability in a public issue when disclosure could put users or servers at risk. Use GitHub's private vulnerability-reporting channel when available, or contact the maintainers privately.

## Proposing features

A feature proposal should explain:

- The concrete gameplay, map-making, server, or mod-integration use case.
- Why the current instance API, command system, or processor API cannot solve it cleanly.
- The expected client-side and server-side behavior.
- OpenAL, decoding, memory, threading, networking, and compatibility implications.
- A small Java or command example when possible.
- Whether the feature belongs in the general audio runtime or in a dependent mod.

GFBS: Auralis is a general audio runtime. Project-specific music systems, cutscene logic, reactor behavior, map conventions, and content assets normally belong in the consuming project.

## Code standards

### Java and public API

- Use Java 17 language features only.
- Follow the existing four-space indentation and brace style.
- Keep public names explicit, stable, and documented.
- Avoid exposing implementation classes when a type belongs in `org.lytharalab.gfbs.auralis.api`.
- Validate arguments at public API, command, packet, decoder, and audio-device boundaries.
- Keep common initialization safe for dedicated servers. Client-only Minecraft and LWJGL classes must not be loaded from server paths.
- Prefer bounded queues, buffers, caches, collections, and packet payloads.
- Do not silently swallow failures that users or integrators need to diagnose.
- Preserve source compatibility unless an approved change deliberately revises the public API.

### OpenAL and threading

- Execute OpenAL device, context, source, and buffer operations on the engine's OpenAL thread.
- Do not call LWJGL OpenAL functions directly from server, network, game-tick, render, or arbitrary worker threads.
- Keep source acquisition and release balanced on every success, failure, cancellation, logout, reload, and shutdown path.
- Never delete a source or buffer while it may still be queued or in use.
- Preserve Minecraft's vanilla audio capacity by respecting the configured source reserve.
- Treat device loss, unsupported HRTF, invalid handles, pool exhaustion, and engine shutdown as normal failure cases that require controlled cleanup.

### Sound instances and source pooling

- Keep mutable playback state per `AuralisSoundInstance`.
- Clamp or reject non-finite volume, pitch, speed, position, priority, and distance values.
- Preserve the distinction between listener-relative static sounds and world-positioned sounds.
- Maintain deterministic priority behavior when recycling sources.
- Do not let stopped or evicted instances retain source ownership.
- Consider replay, pause/resume, looping, late binding, logout, and natural stream completion.

### Streaming and decoding

- Enforce decoded-byte and chunk-size limits before allocating large buffers.
- Keep blocking file work and decoding away from latency-sensitive game and audio-control paths when an asynchronous path is available.
- Validate channels, sample rates, sample formats, frame counts, and decoder return values.
- Recycle or release queued streaming buffers on completion, failure, stop, and shutdown.
- Add tests or reproducible fixtures for truncated, malformed, empty, oversized, mono, and stereo input when changing the decoder.
- Do not add an audio codec dependency without discussing its distribution, native-library, licensing, and platform implications.

### Audio processors and plugins

- `AudioProcessor.process(...)` must remain fast and bounded. Do not perform network requests, filesystem operations, long allocations, sleeps, or uncontrolled locking inside the processing callback.
- Respect the valid region and format of the supplied PCM buffer.
- Keep processor ordering deterministic and preserve the documented priority convention.
- Plugin enable and disable paths must be idempotent enough to clean up partially initialized state.
- Unregister listeners and release plugin-owned resources during shutdown.
- Do not let one plugin failure corrupt the engine or prevent unrelated cleanup.

### Networking, commands, and synchronization

- Treat the server as authoritative for server-orchestrated sound commands.
- Validate identifiers, targets, numeric ranges, positions, entity references, block positions, and packet directions.
- Keep packets bounded and avoid sending raw audio data through the control channel.
- OpenAL work triggered by packets must be enqueued onto the correct client thread or audio thread.
- Consider reconnects, dimension changes, removed entities, unloaded chunks, stale bindings, missing sound events, and clients that initialize late.
- Preserve command-block and server-console behavior: non-player command sources must provide explicit targets.
- Document any command syntax or protocol behavior changed by a pull request.

### Configuration

- Provide safe defaults that work on ordinary OpenAL devices.
- Bound every numeric configuration value.
- Explain restart requirements for settings applied during engine initialization.
- Keep client audio-device settings separate from server policy settings.
- Avoid configuration changes that can starve Minecraft's vanilla sound engine by default.

### Documentation

- Write repository-facing documentation in clear English.
- Document behavior that exists in the submitted code, not planned or private functionality.
- Keep Java and command examples valid for Minecraft Forge 1.20.1.
- Use relative links for repository files and verify that every link resolves.
- Update `README.md` when changing installation, compatibility, commands, configuration, architecture, or public behavior.
- Update this guide when development requirements or validation procedures change.

## Testing

At minimum, a pull request should pass:

```bash
./gradlew build
./gradlew check
```

Manual testing is required whenever the change affects Minecraft integration or the audio device. Test the relevant cases from the following list:

- Client startup and clean shutdown.
- Integrated-server and dedicated-server class loading.
- Buffered and streamed playback.
- Play, pause, resume, stop, replay, looping, and natural completion.
- Listener-relative and world-positioned sound.
- Minimum/maximum distance and attenuation behavior.
- Runtime volume, pitch, speed, position, looping, priority, and distance changes.
- Tween completion and easing behavior.
- Entity binding, block binding, unbinding, entity removal, chunk unload, and dimension change.
- Source-pool exhaustion and priority-based recycling.
- Logout and reconnect cleanup.
- HRTF enabled, disabled, and unsupported-device fallback when available.
- Commands issued by a player, command block, and server console.
- Operation with Minecraft's vanilla sounds playing concurrently.

Include the tested environment, commands or code used, expected result, and actual result in the pull request description.

## Pull request process

1. Create a branch from the current default branch.
2. Make a focused change with clear commits.
3. Add or update validation, documentation, and reproducible fixtures where applicable.
4. Run the relevant Gradle tasks and manual tests.
5. Open a [pull request](https://github.com/LytharaLab/GFBS-Auralis/pulls) with a concise title and complete description.
6. Explain the problem, chosen solution, compatibility impact, tests performed, and remaining limitations.
7. Address review comments with follow-up commits unless maintainers request a different history.

A pull request should not contain generated build output, IDE metadata, local run directories, logs, credentials, private assets, unrelated formatting changes, or bundled third-party binaries unless the repository explicitly requires them.

Maintainers may request changes, split an oversized pull request, or decline a contribution that conflicts with project scope, compatibility, security, attribution requirements, or maintenance capacity.

## Commit messages

Use short, descriptive commit messages in the imperative mood. Examples:

```text
Fix streamed buffer cleanup after stop
Keep OpenAL calls on the audio thread
Validate sound binding packet positions
Add Tween control for attenuation distance
Document source-pool configuration
```

## Licensing

By submitting a contribution, you agree that it will be licensed under the repository's [MIT License](LICENSE).
