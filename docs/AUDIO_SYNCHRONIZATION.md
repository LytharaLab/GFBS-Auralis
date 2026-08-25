# Server-authoritative audio synchronization

GFBS: Auralis keeps server-orchestrated audio as logical, timestamped server state. A joining client receives the current state of each matching instance and starts at the media position implied by the server clock, instead of replaying the original play command from zero.

## Runtime model

Each authoritative instance stores:

- a stable string ID and registered `SoundEvent`;
- buffered or streamed playback mode;
- playing or paused state;
- a media cursor and a `System.nanoTime()` server anchor;
- volume, pitch, speed, position, static/positional mode, looping, priority, and attenuation distances;
- bus assignment and entity/block binding;
- active server-clock Tweens;
- an optional media-duration hint.

The state is immutable and revisioned. Playback position is evaluated from timestamps, including pitch and speed changes, so the server does not tick every instance.

The server runtime uses one shared daemon scheduler for bounded snapshot work and known-duration one-shot expiry. It never creates one thread per sound. Minecraft-owned player lookup and packet delivery stay on the server thread; snapshot assembly and timeline calculation run on the shared worker. The server does not decode audio or call OpenAL.

On the client, resource loading continues through Auralis' bounded asynchronous loader pool. Decoder and OpenAL seeks are marshalled to the existing OpenAL owner thread. No network handler or client tick performs blocking audio work.

## Join and reconnect flow

1. The client sends five short monotonic-clock probes. Four timestamps are used to estimate server-clock offset while excluding server processing time.
2. After the third initial probe, the client requests a snapshot. A two-second server fallback covers clients whose initial request is delayed.
3. The server captures the player's UUID and dimension, then builds an effective global/dimension/player view.
4. Snapshots are split into bounded chunks of at most 32 entries and are applied only after every chunk is present.
5. Revisions preserve deltas that arrive while a snapshot is being assembled. A new server epoch invalidates state from a previous server run.
6. After asynchronous audio creation finishes, the client evaluates the cursor again at the estimated current server time and seeks before audible playback. Paused sounds are created at the right cursor and remain paused.
7. The client evaluates server-clock Tweens continuously and performs periodic drift correction. Dimension changes request a fresh effective snapshot.

Snapshot requests from clients are rate-limited and coalesced per player. Snapshot and packet entry counts are bounded, and the authoritative state limit uses the existing `maxConcurrentSounds` server setting.
Global, dimension, and fixed-player layers share that per-player budget conservatively, so an online delta stream and a later snapshot expose the same bounded set instead of allowing each layer to consume the full limit independently.

## Persistent audiences

Use `AuralisAudience` when an instance must apply to players who are not connected yet:

```java
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.AuralisAudience;
import org.lytharalab.gfbs.auralis.api.AuralisServerApi;

AuralisServerApi.playStreamedSound(
        "lobby_music",
        new ResourceLocation("example", "music.lobby"),
        1.0f,
        1.0f,
        1.0f,
        true,
        Vec3.ZERO,
        true,
        90,
        1.0f,
        64.0f,
        AuralisAudience.all()
);
```

Available scopes are:

| Audience | Behavior |
| --- | --- |
| `AuralisAudience.all()` | Every current player and every player who joins later |
| `AuralisAudience.dimension(level.dimension())` | Players currently or subsequently present in that dimension |
| `AuralisAudience.player(player)` | One fixed player UUID, including reconnects |
| `AuralisAudience.players(players)` | A fixed set of player UUIDs |

The older `Collection<ServerPlayer>` overloads remain source-compatible and use fixed UUID targeting. They do not automatically include unrelated future players.

An unfiltered command target of `@a` is stored as the persistent global audience. Filtered selectors such as `@a[distance=..]` remain fixed to the players selected when the command runs.

## Known-duration one-shots

Pass a positive `durationSeconds` to the duration-aware `playSound` or `playStreamedSound` overload when the server knows the media length. This lets the shared scheduler expire the logical instance without per-instance ticking:

```java
AuralisServerApi.playSound(
        "announcement",
        ANNOUNCEMENT_ID,
        1.0f, 1.0f, 1.0f,
        true, Vec3.ZERO,
        false, 80,
        1.0f, 32.0f,
        12.75,
        AuralisAudience.all()
);
```

Use `0.0` when the server does not know the duration. Clients still suppress a non-looping instance once its locally decoded resource has ended, but the server retains the logical record until `stopSound` is called.

## Control operations

All playback controls update authoritative state before replication:

```java
AuralisServerApi.pauseSound("lobby_music", AuralisAudience.all());
AuralisServerApi.resumeSound("lobby_music", AuralisAudience.all());
AuralisServerApi.setVolume("lobby_music", 0.6f, AuralisAudience.all());
AuralisServerApi.setBus("lobby_music", "Music", AuralisAudience.all());
AuralisServerApi.stopSound("lobby_music", AuralisAudience.all());
```

Volume, pitch, speed, position, static mode, looping, priority, attenuation, bus routing, bindings, and Tweens are included in late-join snapshots. Bus state is revisioned and its hierarchy is validated and bounded by the server runtime.

## Compatibility and security

The mod artifact version remains `2.2.0`; the network protocol is `4`, so the same updated build is required on both client and server. Protocol 4 retains the older packet registrations for compatibility with existing integrations, but client-originated legacy playback control is rejected unless the server explicitly enables `allowClientSync`.
