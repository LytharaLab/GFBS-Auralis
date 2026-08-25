# Audio buses and effects (2.2.0)

## Routing model

`AudioBusSystem` is a client-side, thread-safe routing tree with one permanent root named `Master`.

- Every non-Master bus has exactly one parent.
- Parent depth is unrestricted; self-routing and cycles are rejected.
- A voice belongs to exactly one bus and follows that bus's route to `Master`.
- Removing a bus reparents its direct children to the removed bus's parent. Voices assigned to a removed bus fall back to `Master` on the next engine tick.
- Gain is the product of every bus volume on the route. Mute on any route node makes the route inaudible.
- When at least one bus is soloed, only routes containing a soloed node remain audible.
- Effect bypass applies only to that bus; parent effects and routing remain active.

```java
var buses = AuralisApi.buses();
var ambience = buses.createBus("Ambience");
var cave = buses.createBus("Cave", "Ambience")
        .setVolumeDb(-4.0f)
        .setMuted(false);

sound.setBus("Cave");
```

Bus names are case-sensitive, 1–96 characters long, and accept letters, digits, `_`, `.`, `:`, `/`, and `-`. `setBus` rejects an unknown bus instead of silently changing the route.

`AudioBusSystem.view(name)` exposes an immutable compiled view containing the route, effective volume, audibility, ordered effects, and revision. The hot path uses the same immutable snapshot and rebuilds it only after a bus or effect revision changes.

## Effect order and backends

Effects are collected from the voice's assigned bus toward `Master`. For example:

```text
Voice → Cave effects → Ambience effects → Master effects → output
```

There are three backends:

| Backend | API | Execution model |
| --- | --- | --- |
| Native EFX | `EfxEffect`, `EfxDirectFilterEffect` | Shared native effect/slot/filter objects, attached only to physical OpenAL sources |
| PCM | `PcmEffect` | One processor instance per logical voice, executed as a serial child-to-Master DSP chain |
| Custom OpenAL | `OpenALSourceEffect` | Apply/detach callbacks on the Auralis OpenAL owner thread |

OpenAL EFX auxiliary slots are hardware sends, so multiple native EFX effects are rendered in parallel rather than as a serial PCM transform. The logical list order determines which native sends win when the device's per-source send limit is reached: child-bus effects and earlier entries have priority. Direct filters operate on the dry path. PCM effects retain true serial ordering.

OpenAL exposes one direct filter per source. If a compiled route contains several direct-filter effects, the first active child-to-Master entry wins; use a single band-pass filter when both low- and high-frequency attenuation are needed.

## Built-in OpenAL EFX coverage

The registry exposes every standard EFX 1.0 effect and filter:

| Factory ID | Type |
| --- | --- |
| `gfbs_auralis:reverb` | Reverb |
| `gfbs_auralis:eax_reverb` | EAX reverb |
| `gfbs_auralis:chorus` | Chorus |
| `gfbs_auralis:distortion` | Distortion |
| `gfbs_auralis:echo` | Echo |
| `gfbs_auralis:flanger` | Flanger |
| `gfbs_auralis:frequency_shifter` | Frequency shifter |
| `gfbs_auralis:vocal_morpher` | Vocal morpher |
| `gfbs_auralis:pitch_shifter` | Pitch shifter |
| `gfbs_auralis:ring_modulator` | Ring modulator |
| `gfbs_auralis:autowah` | Autowah |
| `gfbs_auralis:compressor` | Compressor |
| `gfbs_auralis:equalizer` | Equalizer |
| `gfbs_auralis:low_pass` | Low-pass direct filter |
| `gfbs_auralis:high_pass` | High-pass direct filter |
| `gfbs_auralis:band_pass` | Band-pass direct filter |

`EfxParameter` contains typed metadata for every effect parameter, including all EAX reverb vectors. Float and integer values are clamped to the EFX 1.0 range before reaching a driver. `EfxFilterParameter` exposes all low-, high-, and band-pass gains.

```java
EfxFilter sendFilter = new EfxFilter(
        "example:cave_reverb_send",
        EfxFilterType.LOW_PASS
).set(EfxFilterParameter.LOW_PASS_GAIN_HF, 0.25f);

EfxEffect reverb = AuralisEffects.reverb("example:cave_reverb")
        .setFloat(EfxParameter.REVERB_DECAY_TIME, 3.2f)
        .setFloat(EfxParameter.REVERB_DECAY_HF_RATIO, 0.55f)
        .setSendFilter(sendFilter)
        .setWet(0.4f);

AuralisApi.buses().requireBus("Cave").addEffect(reverb);
```

Use filter gain parameters to control filter strength. For direct-filter effects, `wet == 0` bypasses the filter and a positive wet value enables it; the filter's own gain parameters define the response.

At startup Auralis checks `ALC_EXT_EFX` and reads `ALC_MAX_AUXILIARY_SENDS` from its own device. If EFX is absent, the bus graph, gain, mute/solo state, PCM effects, and non-EFX custom OpenAL effects continue working. A driver rejection disables only the affected native object and is logged once.

## Custom PCM effects

Implement `PcmEffect` when an effect should transform decoded 16-bit mono/stereo PCM. Create one stateful processor per logical voice:

```java
public final class SaturationEffect extends AbstractAuralisEffect implements PcmEffect {
    private volatile float drive = 1.0f;

    public SaturationEffect(String id) {
        super(id);
    }

    public SaturationEffect setDrive(float drive) {
        this.drive = Math.max(0.0f, drive);
        markChanged();
        return this;
    }

    @Override
    public AudioProcessor createProcessor() {
        return createProcessor(getWet());
    }

    @Override
    public AudioProcessor createProcessor(float wet) {
        return new SaturationProcessor(drive, wet);
    }
}
```

The processor contract is:

- Modify only the valid region beginning at the buffer's current position.
- Return a non-negative, frame-aligned byte count no larger than the buffer capacity.
- Keep `process` free of file/network I/O, unbounded work, and per-sample allocation.
- Implement `reset` for delay lines, envelopes, or resampler state.
- Increment `getRevision` when a static sound must be rebuilt after a parameter change.
- Release owned direct/native resources in `close`.

Static sounds are decoded and processed asynchronously into a unique OpenAL buffer, then swapped at the current logical cursor. Streamed sounds reuse their decode buffer and run the processor chain on the OpenAL owner thread. A processor exception or invalid byte count is isolated without killing the audio thread: a static build bypasses the failed stage, while a streamed voice disables that processor for its remaining lifetime.

`ProcessorEffect` is a convenience wrapper around either a `Supplier<AudioProcessor>` or a wet-aware `Function<Float, AudioProcessor>`. Implement `PcmEffect` directly when the effect also needs additional mutable parameters.

## Custom source-level OpenAL effects

`OpenALSourceEffect` is the deepest effect hook. `apply` and `detach` run with Auralis' OpenAL context current:

```java
public final class AirAbsorptionEffect extends AbstractAuralisEffect
        implements OpenALSourceEffect {
    private volatile float factor = 1.0f;

    public AirAbsorptionEffect(String id) {
        super(id);
    }

    public AirAbsorptionEffect setFactor(float factor) {
        this.factor = Math.max(0.0f, Math.min(10.0f, factor));
        markChanged();
        return this;
    }

    @Override
    public void apply(OpenALSourceEffectContext context) {
        if (!context.openAL().isEfxSupported()) return;
        AL10.alSourcef(context.sourceId(), EXTEfx.AL_AIR_ABSORPTION_FACTOR, factor);
    }

    @Override
    public void detach(OpenALSourceEffectContext context) {
        if (!context.openAL().isEfxSupported()) return;
        AL10.alSourcef(context.sourceId(), EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0.0f);
    }
}
```

Return `true` from `usesAuxiliarySend()` to reserve the supplied send index. The callback receives `-1` when no index is available and must then bypass its send safely. Do not retain a source ID beyond its apply/detach callback, block the audio thread, or alter source ownership/buffer playback state.

The context deliberately exposes no `AuralisAL`, ALC device/context handle, internal queue, or start/stop operation. General plugin work can use `OpenALAccess.execute`, `call`, or `submit`; source effects already execute on the correct thread.

## Ownership and runtime changes

- An effect is an identity object. Create separate instances for independently configured buses.
- Auralis owns the native EFX objects compiled from an effect and reclaims them after the effect is no longer active or attached.
- `AuralisEffect.close()` is called once during rack shutdown for every observed effect. Custom implementations should make cleanup idempotent.
- `setEnabled`, `setWet`, EFX parameter setters, filter setters, and custom `markChanged()` updates are revision-driven and safe to make at runtime.
- Moving/removing effects or changing bus parents takes effect through the next immutable tick snapshot.

## Server and command control

`AuralisServerApi` supplies client-bound helpers for `createBus`, `removeBus`, `setBusParent`, `setBusVolume`, `setBusMuted`, `setBusSolo`, `setBusEffectsBypassed`, and per-sound `setBus`. Every helper accepts an explicit collection of target players.

The matching commands are documented in the project README. Bus packets use bounded 96-character fields, validate operations on the client, and require protocol version `3`. Effects themselves remain client/plugin objects rather than arbitrary network-serialized code.
