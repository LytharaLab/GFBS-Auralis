# Custom audio data sources (2.3.0)

Auralis 2.3 lets a plugin provide PCM without pretending it is a Minecraft sound resource or encoding generated audio as OGG. The engine owns each created `AudioDataSource`, runs it on the OpenAL owner thread, applies the existing processor/bus/effect chain, and closes it with the logical voice.

## Source contract

An `AudioDataSource` provides signed interleaved PCM16 in mono or stereo. Its immutable `PcmFormat` defines channel count and sample rate. `read(ByteBuffer)` advances the target position by whole PCM frames and returns exactly one state:

| Result | Meaning |
| --- | --- |
| `DATA` | One or more complete PCM frames were written. |
| `WAIT` | No data is available now. The engine retains the OpenAL buffer and retries on a later audio tick. |
| `END` | No more PCM will ever be produced. |

Reads must be non-blocking. File, network, synthesis, and other potentially slow work belongs on plugin-owned workers which publish ready PCM to the source. Auralis deliberately does not create one thread per playback instance.

`TIMELINE` sources must be seekable. They follow Auralis' authoritative logical playback clock and can be virtualized/rematerialized at the correct media position. `LIVE` sources are not rewound when a physical voice is rematerialized; their producer controls buffering and latency. Unknown or live duration is reported as `0`.

## Registering a source factory

```java
public final class RadioPlugin implements AuralisPlugin {
    @Override public String getId() { return "example:radio"; }
    @Override public String getName() { return "Example Radio"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onEnable(PluginContext context) {
        context.registerAudioDataSource("pcm_stream", request -> {
            var source = new PushPcmAudioDataSource(
                    new PcmFormat(2, 48_000, 16),
                    48_000 * 2 * 2
            );
            startProducer(request.resource(), source);
            return source;
        });
    }
}
```

Unqualified IDs inherit the plugin namespace, so the example registers `example:pcm_stream`. The registration is removed automatically if enable rolls back or the plugin unloads. Existing voices continue owning the already-created source; unloading only prevents new factory calls.

Create and configure the voice through the public engine API:

```java
AuralisSoundInstance radio = AuralisApi.create(
        "example:pcm_stream",
        new AudioSourceRequest("wss://audio.example.invalid/live", Map.of("station", "ops"))
);
radio.setStatic(true).setBus("Radio").play();
```

Advanced integrations may construct an `AudioDataSource` directly and transfer ownership with `AuralisApi.create(source)`.

## Dynamic push PCM

`PushPcmAudioDataSource` is a bounded thread-safe `LIVE` implementation. `offer` copies complete PCM frames and returns `false` when the queue is full, allowing the producer to apply its own backpressure/drop policy. An empty unfinished queue yields `WAIT`; call `finish()` to produce `END` after buffered PCM drains. `close()` clears the queue and rejects future input.

## Failure and validation rules

- `DATA` with zero bytes, `WAIT`/`END` with bytes, partial frames, null metadata/results, and unsupported formats are rejected.
- Source read failures terminate that stream without crashing other voices or the OpenAL owner thread.
- Looping requires a seekable source. A non-seekable live source ends normally even if the instance looping flag was set.
- Source methods must not call back into blocking OpenAL operations or wait on work that itself needs the audio thread.
