# Auralis API 2.4

2.4 is an intentionally breaking API release. It replaces fire-and-forget lifecycle calls and client-side server placeholders with explicit operations and separate local/server/client-request entry points.

## Operation contract

`AuralisOperation<T>` has an operation UUID, a `Kind`, a `CompletableFuture<T>`, `onComplete`, `thenApply`, `thenCompose` and cancellation.

- Local creation completes after resource resolution and logical instance registration.
- Local lifecycle operations complete after the state transition and the OpenAL owner-thread barrier required by that transition.
- Server lifecycle operations complete after every target has ACKed, disconnected, been rejected, or reached the configured timeout.
- Sending a packet is not operation completion.
- Invalid-side calls and creation failures complete exceptionally; no silent placeholder is returned.

## Local API

Creation methods on `AuralisApi` and `IAuralisEngine` now return `AuralisOperation<AuralisSoundInstance>`:

```java
AuralisApi.create(event).future().thenCompose(sound -> {
    sound.setVolume(0.75f).setPosition(position);
    return sound.play().future();
});
```

The lifecycle surface is:

```java
AuralisOperation<AuralisSoundSnapshot> play();
AuralisOperation<AuralisSoundSnapshot> pause();
AuralisOperation<AuralisSoundSnapshot> stop();
AuralisOperation<AuralisSoundSnapshot> seek(double seconds);
AuralisOperation<AuralisSoundSnapshot> dispose();
AuralisSoundSnapshot snapshot();
```

Configuration setters remain chainable and update logical state safely before or after materialization. `isMaterialized()` replaces the old physical diagnostic `isBound()`.

## Server API

The dedicated server owns `ServerSoundInstance` state. Create it with a complete immutable `AuralisSoundSpec` and an explicit audience:

```java
AuralisServerApi.create(server, "qserf.alarm", spec, players)
    .future()
    .thenCompose(sound -> sound.play().future());
```

The handle supports `snapshot`, `play`, `pause`, `stop`, `seek`, `update` and `dispose`. Mutating a stale handle fails; recreating the same ID creates a new epoch so delayed packets cannot affect the replacement.

`ServerOperationResult.clients()` contains `APPLIED`, `STALE`, `REJECTED`, `TIMED_OUT` or `DISCONNECTED` for every intended recipient. `allApplied()` accepts `APPLIED` and idempotent `STALE` results.

Global state has no instance setter:

```java
AuralisServerApi.setGlobal(serverInstance);
AuralisServerApi.clearGlobal(serverInstance, explicitAudience);
```

This prevents client-side code from granting itself global scope. Global applies to present and future players; an empty explicit audience is not global.

## Client request API

`AuralisClientApi` is optional and server-controlled. Requests are disabled by default. Accepted IDs are placed in a per-player namespace, and the client receives completion only after the authoritative operation has finished. Client requests cannot change global scope or another player's sounds.

## Removed API

- `AuralisSoundInstance.bind`, `unbind`, `isBound`
- `IAuralisEngine.bind`, `unbind`
- `createAsync` and `createStreamedAsync` (all create methods are operations now)
- legacy server helpers that directly emitted `SoundControlPacket`
- entity/block binding packets and commands
- remote Tween packets and commands

Local Tween utilities remain available, but synchronized changes must be represented by authoritative state revisions.
