# GFBS-Auralis 2.4.0

## Added

- Completion-aware `AuralisOperation` lifecycle API and immutable local snapshots.
- Server-owned `ServerSoundInstance` and immutable `AuralisSoundSpec`.
- Service-tick authoritative playback with epoch/revision ordering.
- Client execution ACK aggregation and deterministic timeout/disconnect results.
- Chunked atomic late-join and dimension-change snapshots with event-driven state replication.
- RTT midpoint server tick estimator.
- Buffered and streamed physical-cursor calibration.
- Coalesced asynchronous recovery from OpenAL, storage and custom-source stalls.
- Server-only global scope with present/future-player semantics.
- Optional validated, per-player-namespaced client request API.
- Deterministic synchronization self-test attached to Gradle `check`.

## Changed

- Protocol version is now 4 and is intentionally incompatible with 2.3 clients/servers.
- All create methods return operations; separate async-named variants are unnecessary.
- Play, pause and stop return operations, and seek/dispose are explicit lifecycle methods.
- Voice allocation is entirely automatic and exposed diagnostically as materialized/virtualized.
- Commands use the authoritative state machine. `/auralis` and `/gfbs_auralis` are equivalent OP2 roots.

## Removed

- Public bind/unbind functionality, entity/block binding and their packet surface.
- Silent server placeholder sound instances.
- Legacy play/stop/loop/sync/control packets.
- Remote Tween commands/packets that could be overwritten by the next authoritative snapshot.
- Per-instance bus routing packets outside the authoritative sound specification.

## Migration

See `docs/API_2.4.md`. Consumers must chain from operation futures and replace direct legacy server helper calls with `AuralisServerApi.create` plus `ServerSoundInstance` operations.
