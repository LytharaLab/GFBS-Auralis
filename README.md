# GFBS-Auralis 2.4.0

Independent OpenAL-based 3D audio engine for Minecraft Forge 1.20.1, with hierarchical buses, effects, custom PCM sources, voice virtualization, server-authoritative playback and real-time timeline calibration.

Java 17 and Forge 47.x are required. Install Auralis on both sides when authoritative synchronization is used. The dedicated server stores state only: it never opens OpenAL, downloads sound data or decodes PCM.

## What changed in 2.4

- One operation model for creation and lifecycle control. Completion now means that the logical transition and all already-required OpenAL work have committed.
- Server-owned sound instances with service-tick anchors, monotonic epochs/revisions and per-client execution acknowledgements.
- Atomic chunked snapshots for late join, dimension changes and periodic reconciliation.
- A real-time calibrator that compares the server projection with the sampled physical OpenAL cursor.
- Small drift is corrected gradually; large drift, a stopped renderer or a stale cursor after an audio-thread/disk-I/O stall triggers an asynchronous hard seek.
- Streamed playback tracks the duration of every queued OpenAL buffer, so calibration uses an absolute physical stream position rather than a client wall-clock guess.
- Protocol version 4 replaces the legacy fire-and-forget sound packets.
- The public `bind`, `unbind` and `isBound` API is removed. Voice materialization is automatic; inspect `isMaterialized()` when diagnostics need the physical state.
- Remote Tween and entity/block binding commands are removed because they bypassed authoritative state.

See [API 2.4](docs/API_2.4.md), [authoritative synchronization](docs/AUTHORITATIVE_SYNC_2.4.md), and [2.4.0 release notes](docs/RELEASE_NOTES_2.4.0.md).

## Local API

```java
AuralisApi.create(MY_SOUND).future()
    .thenCompose(sound -> {
        sound.setVolume(0.8f)
             .setPosition(worldPosition)
             .setMinDistance(2.0f)
             .setMaxDistance(64.0f);
        return sound.play().future();
    })
    .thenAccept(committed -> {
        // PLAY is committed; follow-up lifecycle work is now safe.
    });
```

`createStreamed`, custom `AudioDataSource` creation and registered source factories return the same `AuralisOperation` type. Failures are explicit exceptional completions; Auralis no longer returns silent placeholder voices.

```java
sound.pause().future()
    .thenCompose(paused -> sound.seek(12.5).future())
    .thenCompose(seeked -> sound.play().future());
```

Logical voices may be virtual when they are inaudible or the physical source budget is exhausted. Their timelines continue, and `isMaterialized()` reports whether an OpenAL source is currently attached.

## Server-authoritative API

```java
AuralisSoundSpec spec = AuralisSoundSpec.builder(MY_SOUND)
    .streamed(true)
    .volume(0.9f)
    .position(origin)
    .looping(true)
    .distances(2.0f, 96.0f)
    .build();

AuralisServerApi.create(server, "facility.reactor_hum", spec, targetPlayers)
    .future()
    .thenCompose(instance -> instance.play().future())
    .thenAccept(result -> {
        if (!result.allApplied()) {
            // Inspect result.clients() for rejected, disconnected or timed-out clients.
        }
    });
```

Only the server API can make a sound global:

```java
AuralisServerApi.setGlobal(serverInstance);
```

Global means all players connected now and all players who join later. It is never inferred from an empty selector and cannot be enabled through a client handle. `clearGlobal(instance, audience)` atomically returns to an explicit audience and disposes excluded client mirrors.

## Optional client requests

Client requests are disabled by default. If a server enables `allowClientRequests`, `AuralisClientApi` can create a client-owned authoritative sound. The server namespaces it to that player, validates every request, broadcasts the resulting state, waits for execution ACKs, and only then returns the request result. Clients cannot set global state.

## Commands

Both `/auralis` and the compatibility alias `/gfbs_auralis` require permission level 2.

```text
/auralis play <id> <sound-event> <targets>
/auralis stream <id> <sound-event> <targets>
/auralis pause|stop|dispose <id>
/auralis seek <id> <seconds>
/auralis global <id>
/auralis audience <id> <targets>
/auralis volume|pitch|speed|looping|position ...
/auralis bus create|remove|parent|volume|muted|solo|bypass ...
```

Bus topology and effect APIs remain available through `AuralisApi.buses()`, `effects()`, `plugins()`, `openAL()` and `dataSources()`. See [Audio buses and effects](docs/AUDIO_BUSES_AND_EFFECTS.md), [plugin API](docs/PLUGIN_API_2.2.md), and [custom audio data sources](docs/CUSTOM_AUDIO_DATA_SOURCES_2.3.md).

## Build and verification

```bash
./gradlew check
./gradlew build
```

`check` includes a deterministic timeline synchronization self-test. Runtime testing should cover dedicated-server late join, disconnect during an operation, streamed seek/loop, pause/resume, source virtualization, and an intentionally stalled audio data source.

## License

MIT. See [LICENSE](LICENSE).
