package org.lytharalab.gfbs.auralis;
/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 * <p>
 * This program is licensed under the MIT License.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is provided to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class GFBsAuralisConfig {
    public static class ServerConfig {
        public final ForgeConfigSpec.IntValue maxConcurrentSounds;
        public final ForgeConfigSpec.DoubleValue defaultVolume;
        public final ForgeConfigSpec.BooleanValue enableRemoteSounds;
        public final ForgeConfigSpec.BooleanValue allowClientSync;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("Server configuration for GFBS-Auralis")
                    .push("general");

            maxConcurrentSounds = builder
                    .comment("Maximum number of concurrent logical sounds per player")
                    .defineInRange("maxConcurrentSounds", 1024, 1, 4096);

            defaultVolume = builder
                    .comment("Default volume for sounds (0.0 to 1.0)")
                    .defineInRange("defaultVolume", 1.0, 0.0, 1.0);

            enableRemoteSounds = builder
                    .comment("Enable playing sounds from remote locations")
                    .define("enableRemoteSounds", true);

            allowClientSync = builder
                    .comment("Allow client->server sound state sync packets (not recommended)")
                    .define("allowClientSync", false);

            builder.pop();
        }
    }

    public static class ClientConfig {
        public final ForgeConfigSpec.IntValue maxSources;
        public final ForgeConfigSpec.IntValue reserveSourcesForVanilla;
        public final ForgeConfigSpec.IntValue streamedChunkSize;
        public final ForgeConfigSpec.IntValue maxStreamedBytes;
        public final ForgeConfigSpec.DoubleValue attenuationExponent;
        public final ForgeConfigSpec.DoubleValue volumeSmoothing;
        public final ForgeConfigSpec.DoubleValue voiceMaterializeGain;
        public final ForgeConfigSpec.DoubleValue voiceVirtualizeGain;
        public final ForgeConfigSpec.BooleanValue enableHrtf;

        ClientConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("Client configuration for GFBS-Auralis")
                    .push("audio");

            maxSources = builder
                    .comment("Maximum number of physical OpenAL sources (0 = auto-detect; excess logical voices are virtualized)")
                    .defineInRange("maxSources", 0, 0, 1024);

            reserveSourcesForVanilla = builder
                    .comment("Reserve some OpenAL sources for Minecraft vanilla sound engine")
                    .defineInRange("reserveSourcesForVanilla", 8, 0, 256);

            streamedChunkSize = builder
                    .comment("PCM chunk size (bytes) for streamed sounds")
                    .defineInRange("streamedChunkSize", 32768, 4096, 262144);

            maxStreamedBytes = builder
                    .comment("Maximum decoded PCM bytes for a streamed sound (safety limit)")
                    .defineInRange("maxStreamedBytes", 16 * 1024 * 1024, 256 * 1024, 256 * 1024 * 1024);

            attenuationExponent = builder
                    .comment("Distance attenuation curve exponent (1.0 = linear)")
                    .defineInRange("attenuationExponent", 1.35, 0.1, 8.0);

            volumeSmoothing = builder
                    .comment("Per-tick volume smoothing factor (0..1)")
                    .defineInRange("volumeSmoothing", 0.35, 0.0, 1.0);

            voiceMaterializeGain = builder
                    .comment("Predicted audible gain required for a virtual logical voice to acquire a physical OpenAL source")
                    .defineInRange("voiceMaterializeGain", 0.0010, 0.0, 1.0);

            voiceVirtualizeGain = builder
                    .comment("Predicted audible gain below which a physical voice becomes virtual; keep this <= voiceMaterializeGain for hysteresis")
                    .defineInRange("voiceVirtualizeGain", 0.00025, 0.0, 1.0);

            enableHrtf = builder
                    .comment("Enable OpenAL HRTF if supported by the device")
                    .define("enableHrtf", false);

            builder.pop();
        }
    }

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ClientConfig CLIENT;

    static {
        final Pair<ServerConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_SPEC = specPair.getRight();
        SERVER = specPair.getLeft();

        final Pair<ClientConfig, ForgeConfigSpec> clientSpecPair = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT_SPEC = clientSpecPair.getRight();
        CLIENT = clientSpecPair.getLeft();
    }
}
