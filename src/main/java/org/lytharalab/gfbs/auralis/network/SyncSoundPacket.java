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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.GFBsAuralisConfig;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.function.Supplier;

public class SyncSoundPacket {
    private final ResourceLocation soundId;
    private final float volume;
    private final float pitch;
    private final boolean looping;

    public SyncSoundPacket(ResourceLocation soundId, float volume, float pitch, boolean looping) {
        this.soundId = soundId;
        this.volume = volume;
        this.pitch = pitch;
        this.looping = looping;
    }

    public static void encode(SyncSoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.soundId);
        buffer.writeFloat(packet.volume);
        buffer.writeFloat(packet.pitch);
        buffer.writeBoolean(packet.looping);
    }

    public static SyncSoundPacket decode(FriendlyByteBuf buffer) {
        ResourceLocation soundId = buffer.readResourceLocation();
        float volume = buffer.readFloat();
        float pitch = buffer.readFloat();
        boolean looping = buffer.readBoolean();
        return new SyncSoundPacket(soundId, volume, pitch, looping);
    }

    public static void handle(SyncSoundPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null) {
                if (!GFBsAuralisConfig.SERVER.allowClientSync.get()) {
                    return;
                }
                float volume = Mth.clamp(packet.volume, 0.0f, 1.0f);
                float pitch = Mth.clamp(packet.pitch, 0.01f, 8.0f);
                AuralisServerManager.syncSound(player, packet.soundId, volume, pitch, packet.looping);
            }
        });
        context.get().setPacketHandled(true);
    }
}
