# GFBS-Auralis 2.3.1

## Fixed

- Non-streamed sounds that can be materialized immediately now start at sample zero instead of seeking past the client-scheduler delay.
- Very short buffered sounds can no longer finish on the logical clock before their first physical scheduling opportunity.
- The logical playback clock is rebased to the successful initial `alSourcePlay` operation, keeping duration and natural-completion timing aligned with audible playback.

## Compatibility and virtualization guarantees

- The correction applies only to the first physical scheduling opportunity of a new non-streamed playback cycle.
- A voice that remains virtual because of attenuation, priority, Source pressure, cancellation, or allocation failure still accounts for the full elapsed logical time.
- Later virtual-to-physical transitions continue to seek to the authoritative logical cursor.
- Pause/resume, stop/replay, looping, streamed OGG playback, and custom 2.3 audio data sources retain their existing behavior.
- Playback generations prevent a stale asynchronous scheduler pass from rebasing a newer stop/replay cycle.
