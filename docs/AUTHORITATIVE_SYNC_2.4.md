# Authoritative synchronization in Auralis 2.4

## Authority model

The Minecraft server service tick is the canonical time domain. Each sound has an epoch, revision, playback state, service-tick anchor, media-position anchor, immutable specification, learned duration, explicit audience and global flag.

The client monotonic clock is used only to interpolate an estimate between RTT probes. It is never the primary timeline. A probe maps the request/response midpoint to a server tick; a discontinuity greater than two ticks causes a hard re-anchor.

The dedicated server does not initialize OpenAL, resolve sound resources or decode audio. Clients are mirrors of server state.

## State delivery

- A changed state increments its revision and is sent with an operation UUID.
- Clients reject old epochs/revisions and ACK duplicate state as `STALE`.
- An ACK is emitted only after client application and the necessary audio-thread barrier complete.
- Server operations aggregate all client results and time out deterministically.
- Recreating an ID uses a new epoch and sends a disposal tombstone for the old epoch.
- Full snapshots are assembled from bounded chunks before application. They repair missing state and remove mirrors no longer visible to that player.
- Login, dimension change and periodic heartbeats send full snapshots.

## Real-time physical cursor calibration

The calibrator compares the expected server position with the actual renderer position, not merely the client's logical clock.

For buffered audio, Auralis samples OpenAL `AL_SEC_OFFSET`. For streamed audio it records the media duration placed in every OpenAL buffer, advances an absolute base when processed buffers are unqueued, and adds the current device offset. A fresh sample may be extrapolated for at most 250 ms. An older sample is deliberately held still so a blocked audio thread is observable.

Default policy:

| Physical error | Action |
|---|---|
| at most 25 ms | restore normal rate |
| between 25 ms and 750 ms | converge over 2 seconds, capped at ±4% |
| at least 750 ms | asynchronous hard seek |
| renderer stopped while server is still playing | asynchronous hard seek and restart |

Looping sounds use the shortest wrapped error. Hard seeks are coalesced: while disk I/O or a custom source blocks the single OpenAL owner thread, new calibration targets replace the old pending target. Once the thread resumes, it repairs to the newest server position instead of replaying a queue of stale seeks.

## Disk-I/O stall sequence

1. Disk I/O or a custom `AudioDataSource.read` blocks the OpenAL thread.
2. The last physical sample becomes stale and stops being extrapolated.
3. The server tick estimate continues advancing from the authoritative service timeline.
4. Drift crosses the hard threshold; one coalesced repair is queued.
5. Further ticks update that repair to the newest expected position.
6. When I/O resumes, the OpenAL thread rebuilds/seeks the stream and restarts from the current authoritative position.

The Minecraft client thread never waits for this repair. Operation ACKs that require the repair are ordered behind its audio-thread commit barrier.

## Natural completion

The first valid client ACK may teach the server a finite duration (bounded to 24 hours). The server then decides non-looping natural completion from its service-tick projection. Clients do not revive a local renderer once the expected position reaches the known end; they wait for the server's final stopped revision.

## Global semantics

Explicit audience and global scope are distinct. `setGlobal` includes every connected player and causes future login snapshots to include the sound. `clearGlobal` publishes one new revision to the retained audience and a disposal tombstone of the same epoch/revision to excluded clients.
