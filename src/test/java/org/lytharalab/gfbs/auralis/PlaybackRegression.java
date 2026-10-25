package org.lytharalab.gfbs.auralis;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.auralis.api.AuralisPlaybackParameters;
import org.lytharalab.gfbs.auralis.core.bus.AuralisBusManager;
import org.lytharalab.gfbs.auralis.network.SoundControlPacket;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;
import org.lytharalab.gfbs.auralis.tween.TweenInfo;
import org.lytharalab.gfbs.auralis.tween.TweenService;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.SOFTLoopback.*;
import static org.lwjgl.openal.SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT;

/** Standalone checks without a test-framework dependency; --native renders real OpenAL loopback PCM. */
public final class PlaybackRegression {
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAMES = 8_192;
    private static int checks;

    public static void main(String[] args) {
        parametersAndRouting();
        packetsAndTween();
        bufferedStartGuard();
        if (args.length > 0) {
            if (args.length != 1 || !args[0].equals("--native")) throw new IllegalArgumentException("Expected --native");
            nativePlayback();
        }
        System.out.println("Playback regression PASS: " + checks + " checks");
    }

    private static void parametersAndRouting() {
        near(32, AuralisPlaybackParameters.pitch(32), 0, "pitch 32 survives validation");
        near(32, AuralisPlaybackParameters.pitch(128), 0, "pitch ceiling");
        near(0.01, AuralisPlaybackParameters.pitch(-1), 0.000001, "pitch floor");
        near(1, AuralisPlaybackParameters.pitch(Float.NaN), 0, "NaN pitch");
        near(1, AuralisPlaybackParameters.pitch(Float.POSITIVE_INFINITY), 0, "infinite pitch");
        near(8, AuralisPlaybackParameters.speed(32), 0, "existing speed ceiling");
        near(32, AuralisPlaybackParameters.playbackRate(32, 1), 0, "submitted and virtual rate 32");
        near(32, AuralisPlaybackParameters.playbackRate(16, 4), 0, "combined rate ceiling");
        near(16, AuralisPlaybackParameters.playbackRate(32, 0.5f), 0, "speed scales high pitch");
        near(2, AuralisPlaybackParameters.volume(2), 0, "synchronized volume above unity");
        near(0, AuralisPlaybackParameters.volume(Float.NaN), 0, "NaN volume");
        near(0, AuralisPlaybackParameters.volume(Float.POSITIVE_INFINITY), 0, "infinite volume");
        near(Float.MAX_VALUE, AuralisPlaybackParameters.combinedVolume(Float.MAX_VALUE, 16), 0,
                "finite combined volume cannot overflow smoothing");

        var buses = new AuralisBusManager();
        var output = buses.createBus("GFBsBus");
        var event = buses.createBus("Event", "GFBsBus");
        buses.createBus("Dmr", "Event");
        near(1, buses.snapshot().route("Dmr").gain(), 0, "100 percent route");
        output.setVolume(2);
        near(2, buses.snapshot().route("Dmr").gain(), 0, "200 percent live route");
        event.setVolume(0.5f);
        near(1, buses.snapshot().route("Dmr").gain(), 0, "parent/child product");
        output.setMuted(true);
        check(!buses.snapshot().route("Dmr").audible(), "muting amplified route");
        output.setMuted(false);
        event.setVolume(1);
        near(2, buses.snapshot().route("Dmr").gain(), 0, "unmute restores amplified route");
        buses.reset();
        near(1, buses.snapshot().route("Dmr").gain(), 0, "world reset clears old gain");
    }

    private static void bufferedStartGuard() {
        var guard = new InitialBufferedPlaybackGuard();
        long token = guard.beginNewPlayback(true);
        check(guard.claimForScheduling() == token, "initial buffered start claimed once");
        check(guard.claimForScheduling() == InitialBufferedPlaybackGuard.NONE, "no repeated clock rebase");
        guard.invalidate();
        check(!guard.isCurrent(token), "stop invalidates pending start");
        guard.beginNewPlayback(false);
        check(guard.claimForScheduling() == InitialBufferedPlaybackGuard.NONE, "virtual start remains virtual");
    }

    private static void packetsAndTween() {
        for (var action : new SoundControlPacket.Action[]{SoundControlPacket.Action.PLAY,
                SoundControlPacket.Action.STREAMED_PLAY, SoundControlPacket.Action.SET_PITCH}) {
            var packet = new SoundControlPacket(action, "pitch32", new ResourceLocation("minecraft:empty"),
                    2, 32, 1, true, 0, 0, 0, false, 50, 1, 48);
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SoundControlPacket.encode(packet, bytes);
                var decoded = SoundControlPacket.decode(bytes);
                near(32, decoded.pitch, 0, action + " pitch round trip");
                near(2, decoded.volume, 0, action + " amplified volume round trip");
                check(bytes.readableBytes() == 0, action + " fixed packet layout consumed");
            } finally {
                bytes.release();
            }
        }
        var packet = new TweenControlPacket(TweenControlPacket.Property.PITCH, "pitch32", 32, 0, 0,
                1, EasingStyle.LINEAR, EasingDirection.OUT);
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TweenControlPacket.encode(packet, bytes);
            near(32, TweenControlPacket.decode(bytes).targetX, 0, "tween target round trip");
        } finally {
            bytes.release();
        }
        float[] pitch = {1};
        var tweens = new TweenService();
        tweens.create(() -> pitch[0], value -> pitch[0] = AuralisPlaybackParameters.pitch((float) value),
                TweenInfo.of(1).easing(EasingStyle.LINEAR, EasingDirection.OUT).build(), 32).play();
        tweens.tick(0.5);
        near(16.5, pitch[0], 0, "tween crosses old pitch ceiling");
        tweens.tick(0.5);
        near(32, pitch[0], 0, "tween reaches pitch 32");
        check(tweens.size() == 0, "completed pitch tween released");
    }

    private static void nativePlayback() {
        long device = alcLoopbackOpenDeviceSOFT((String) null);
        check(device != 0, "loopback device");
        long context = 0;
        try {
            ALCCapabilities caps = ALC.createCapabilities(device);
            int[] attrs = caps.ALC_SOFT_output_limiter
                    ? new int[]{ALC_FREQUENCY, SAMPLE_RATE, ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
                        ALC_FORMAT_TYPE_SOFT, ALC_FLOAT_SOFT, ALC_OUTPUT_LIMITER_SOFT, ALC_FALSE, 0}
                    : new int[]{ALC_FREQUENCY, SAMPLE_RATE, ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
                        ALC_FORMAT_TYPE_SOFT, ALC_FLOAT_SOFT, 0};
            context = alcCreateContext(device, attrs);
            check(context != 0 && alcMakeContextCurrent(context), "loopback context");
            AL.createCapabilities(caps);
            alDistanceModel(AL_NONE);
            alDopplerFactor(0);
            float limit = AuralisAL.querySourceGainLimitOnALThread();
            check(limit >= 2, "loopback driver supports amplification");
            System.out.println("OpenAL " + alGetString(AL_VERSION) + "; gain limit=" + limit);
            renderVoice(device, limit, false);
            renderVoice(device, limit, true);
            noError("native playback complete");
        } finally {
            alcMakeContextCurrent(0);
            if (context != 0) alcDestroyContext(context);
            alcCloseDevice(device);
        }
    }

    private static void renderVoice(long device, float limit, boolean streamed) {
        String mode = streamed ? "queued stream" : "buffered";
        int source = alGenSources();
        int buffer = alGenBuffers();
        ShortBuffer pcm = MemoryUtil.memAllocShort(SAMPLE_RATE * 20);
        try {
            for (int i = 0; i < pcm.capacity(); i++) {
                pcm.put(i, (short) (1_000 * Math.sin(2 * Math.PI * 100 * i / SAMPLE_RATE)));
            }
            alBufferData(buffer, AL_FORMAT_MONO16, pcm, SAMPLE_RATE);
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSourcef(source, AL_ROLLOFF_FACTOR, 0);
            alSource3f(source, AL_POSITION, 0, 0, 0);
            if (streamed) alSourceQueueBuffers(source, buffer);
            else {
                alSourcei(source, AL_BUFFER, buffer);
                alSourcei(source, AL_LOOPING, AL_TRUE);
            }
            // Reproduce the old failure using the driver's default MAX_GAIN=1.
            double oldUnity = measure(device, source, 1, 1).rms();
            double oldDouble = measure(device, source, 2, 1).rms();
            near(1, oldDouble / oldUnity, 0.02, mode + " old unity ceiling reproduced");

            AuralisAL.configureSourceGainOnALThread(source, limit);
            near(limit, alGetSourcef(source, AL_MAX_GAIN), 0, mode + " native ceiling configured");
            double half = measure(device, source, 0.5f, 1).rms();
            alSourcef(source, AL_GAIN, 1);
            double unity = capture(device).rms();
            alSourcef(source, AL_GAIN, 2);
            double doubled = capture(device).rms();
            near(0.5, half / unity, 0.02, mode + " 50 percent output");
            near(2, doubled / unity, 0.02, mode + " 200 percent output");
            alSourcef(source, AL_GAIN, 0);
            near(0, capture(device).rms(), 0.000001, mode + " mute");
            Measurement highPitch = measure(device, source, 1,
                    AuralisPlaybackParameters.playbackRate(32, 1));
            near(32, alGetSourcef(source, AL_PITCH), 0, mode + " driver accepts pitch 32");
            check(highPitch.frequency() >= 785 && highPitch.frequency() <= 3_215,
                    mode + " native pitch remains audible within the requested range");
            Measurement slower = measure(device, source, 1,
                    AuralisPlaybackParameters.playbackRate(32, 0.5f));
            near(16, alGetSourcef(source, AL_PITCH), 0, mode + " high pitch times speed submitted");
            check(slower.frequency() > 0 && slower.frequency() <= highPitch.frequency() + 15,
                    mode + " driver-limited speed response");
            System.out.printf("%s requested pitch=32, native property=32, measured effective rate=%.3f (driver dependent)%n",
                    mode, highPitch.frequency() / 100);
            alSourcef(source, AL_GAIN, 2);
            alSourcef(source, AL_PITCH, 1);
            alSourcePause(source);
            check(alGetSourcei(source, AL_SOURCE_STATE) == AL_PAUSED, mode + " paused");
            alSourcePlay(source);
            near(2, capture(device).rms() / unity, 0.02, mode + " pause/resume gain");
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            alSource3f(source, AL_POSITION, 0, 0, -10);
            near(2, capture(device).rms() / unity, 0.02, mode + " world-positioned gain");

            // A plugin/previous owner may have changed both bounds before pool reuse.
            alSourceStop(source);
            alSourcef(source, AL_MIN_GAIN, 0.5f);
            alSourcef(source, AL_MAX_GAIN, 1);
            AuralisAL.configureSourceGainOnALThread(source, limit);
            near(0, alGetSourcef(source, AL_GAIN), 0, mode + " recycled source silent");
            near(0, alGetSourcef(source, AL_MIN_GAIN), 0, mode + " recycled minimum gain reset");
            near(2, measure(device, source, 2, 1).rms() / unity, 0.02, mode + " recycled source amplification");
            // Legacy-safe configuration never sends a MAX_GAIN > 1 to a legacy driver.
            AuralisAL.configureSourceGainOnALThread(source, 1);
            near(1, alGetSourcef(source, AL_MAX_GAIN), 0, mode + " legacy configuration");
            noError(mode);
            System.out.printf("%s RMS: 50%%=%.6f, 100%%=%.6f, 200%%=%.6f%n", mode, half, unity, doubled);
        } finally {
            alSourceStop(source);
            alDeleteSources(source);
            alDeleteBuffers(buffer);
            MemoryUtil.memFree(pcm);
        }
    }

    private record Measurement(double rms, double frequency) {}

    private static Measurement measure(long device, int source, float gain, float rate) {
        alSourceStop(source);
        alSourceRewind(source);
        alSourcef(source, AL_GAIN, gain);
        alSourcef(source, AL_PITCH, rate);
        alSourcePlay(source);
        return capture(device);
    }

    private static Measurement capture(long device) {
        FloatBuffer output = MemoryUtil.memAllocFloat(FRAMES * 2);
        try {
            alcRenderSamplesSOFT(device, output, FRAMES); // Settle the native gain ramp.
            alcRenderSamplesSOFT(device, output, FRAMES);
            double sum = 0;
            int crossings = 0;
            for (int i = 0; i < FRAMES; i++) {
                float sample = output.get(i * 2);
                sum += sample * sample;
                if (i > 0 && output.get((i - 1) * 2) <= 0 && sample > 0) crossings++;
            }
            noError("render");
            return new Measurement(Math.sqrt(sum / FRAMES), crossings * (double) SAMPLE_RATE / FRAMES);
        } finally {
            MemoryUtil.memFree(output);
        }
    }

    private static void noError(String label) {
        int error = alGetError();
        check(error == AL_NO_ERROR, label + " AL error=0x" + Integer.toHexString(error));
    }

    private static void near(double expected, double actual, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
                label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
