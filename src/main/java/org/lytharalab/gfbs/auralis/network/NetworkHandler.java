package org.lytharalab.gfbs.auralis.network;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.lytharalab.gfbs.auralis.GFBsAuralis;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GFBsAuralis.MODID, "gfbs_auralis_main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void registerMessages() {
        registerMessage(SyncSoundPacket.class, SyncSoundPacket::encode, SyncSoundPacket::decode, SyncSoundPacket::handle);
        registerMessage(PlaySoundPacket.class, PlaySoundPacket::encode, PlaySoundPacket::decode, PlaySoundPacket::handle);
        registerMessage(StopSoundPacket.class, StopSoundPacket::encode, StopSoundPacket::decode, StopSoundPacket::handle);
        registerMessage(LoopSoundPacket.class, LoopSoundPacket::encode, LoopSoundPacket::decode, LoopSoundPacket::handle);

        // New: server->client sound control for the /auralis command
        registerMessage(SoundControlPacket.class, SoundControlPacket::encode, SoundControlPacket::decode, SoundControlPacket::handle);

        // New: server->client tween control for the /auralis tween command
        registerMessage(TweenControlPacket.class, TweenControlPacket::encode, TweenControlPacket::decode, TweenControlPacket::handle);

        registerMessage(BindControlPacket.class, BindControlPacket::encode, BindControlPacket::decode, BindControlPacket::handle);
        registerMessage(BusControlPacket.class, BusControlPacket::encode, BusControlPacket::decode, BusControlPacket::handle);

        // Protocol 4: server-authoritative timeline, NTP-style monotonic clock
        // calibration, idempotent deltas, and bounded atomic late-join snapshots.
        registerMessage(AudioClockSyncRequestPacket.class,
                AudioClockSyncRequestPacket::encode,
                AudioClockSyncRequestPacket::decode,
                AudioClockSyncRequestPacket::handle);
        registerMessage(AudioClockSyncResponsePacket.class,
                AudioClockSyncResponsePacket::encode,
                AudioClockSyncResponsePacket::decode,
                AudioClockSyncResponsePacket::handle);
        registerMessage(AudioStateDeltaPacket.class,
                AudioStateDeltaPacket::encode,
                AudioStateDeltaPacket::decode,
                AudioStateDeltaPacket::handle);
        registerMessage(AudioBusStatePacket.class,
                AudioBusStatePacket::encode,
                AudioBusStatePacket::decode,
                AudioBusStatePacket::handle);
        registerMessage(AudioStateSnapshotPacket.class,
                AudioStateSnapshotPacket::encode,
                AudioStateSnapshotPacket::decode,
                AudioStateSnapshotPacket::handle);
    }

    private static <MSG> void registerMessage(Class<MSG> messageType,
                                              BiConsumer<MSG, FriendlyByteBuf> encoder,
                                              Function<FriendlyByteBuf, MSG> decoder,
                                              BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer) {
        CHANNEL.registerMessage(packetId++, messageType, encoder, decoder, messageConsumer);
    }
}
