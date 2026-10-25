# GFBS-Auralis 2.3.2

## Amplification fix

- Instance and hierarchical bus gain above `1` no longer encounter OpenAL's default `AL_MAX_GAIN = 1` ceiling on supported devices. In particular, a consuming mod's 100% to 200% bus-volume change can now double the actual linear PCM amplitude.
- Auralis detects `AL_SOFT_gain_clamp_ex` on its OpenAL owner thread, queries `AL_GAIN_LIMIT_SOFT`, and configures both fresh and reused sources with that limit. Source gain is silenced and the minimum gain restored to zero before a new owner starts playback.
- Initial playback and live volume updates use the same device-bounded gain path. Distance attenuation, gain smoothing, routing, mute/solo, and vanilla Minecraft's separate audio context retain their existing roles.
- Unsupported devices retain the valid unity ceiling and log a startup warning. A malformed gain-limit query also logs a warning and falls back safely. Output limiters and clipping can still restrict loud signals; 200% means twice the linear amplitude, not twice the perceived loudness.
- Legacy server sound-state synchronization now preserves finite nonnegative instance volumes above unity. Combined volume calculations saturate before float overflow can poison smoothing or native gain.

## Pitch parameters up to 32

- Local instances, buffered/streamed playback commands, regulating commands, sound-control packets, Tween pitch targets, and legacy synchronized sound state accept pitch through `32` instead of `8`.
- Pitch remains bounded to `0.01`–`32`. Speed retains its `0.01`–`8` range. The logical playback clock and submitted OpenAL pitch use the same combined `pitch * speed` clamp of `0.01`–`32`.
- Commands reject pitch arguments above `32`; APIs and packets clamp them. Existing zero-valued command inputs still resolve to the `0.01` playback floor. Non-finite pitch defaults to `1`, and non-finite volume becomes zero.
- This release raises the parameter limits only. It does **not** add software resampling or replace the native library. The driver's resampling step can cap the actual audible rate below the requested value. With the bundled OpenAL Soft 1.21.1 and matching 48 kHz input/output, the native regression measured approximately 10-times output while `AL_PITCH` read back as `32`. At such a driver ceiling the audible position can fall behind the logical clock, which continues to use the requested combined rate.
- No packet fields, channel protocol version, public method signatures, Forge/Minecraft/Java versions, or consuming-mod dependencies are changed. Older clients can still apply their own previous pitch ceiling; use 2.3.2 on each participating side for consistent parameter handling.

## Verification

```powershell
.\gradlew.bat build check
.\gradlew.bat nativePlaybackRegression
```

`check` includes framework-free parameter, bus-routing, packet-codec, Tween, and initial-buffered-start regressions. `nativePlaybackRegression` additionally requires the platform's LWJGL/OpenAL natives and an OpenAL Soft loopback device with amplification support. It renders low-level PCM without sending audio to speakers, reproduces the old unity ceiling, measures live 50%/100%/200% gain, and checks buffered/queued playback, positional output, mute, pause/resume, recycled source bounds, and native pitch-property acceptance. It reports the measured audible high-pitch rate separately rather than treating property acceptance as proof of audible 32-times playback.

These regressions do not replace an in-game check of a consuming mod's configuration UI, resource packs, multiplayer synchronization, or a particular user's physical output device.
