package org.lytharalab.gfbs.auralis.api;
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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.network.BindControlPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;
import org.lytharalab.gfbs.auralis.network.SoundControlPacket;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collection;

public class AuralisServerApi {

    public static int playSound(String id, ResourceLocation soundEventId, float volume, float pitch, float speed, boolean isStatic,
                                Vec3 pos, boolean looping, int priority, float minDistance, float maxDistance,
                                Collection<ServerPlayer> targets) {
        return playSoundInternal(id, soundEventId, volume, pitch, speed, isStatic, pos, looping, priority, minDistance, maxDistance, targets, false);
    }

    public static int playStreamedSound(String id, ResourceLocation soundEventId, float volume, float pitch, float speed, boolean isStatic,
                                      Vec3 pos, boolean looping, int priority, float minDistance, float maxDistance,
                                      Collection<ServerPlayer> targets) {
        return playSoundInternal(id, soundEventId, volume, pitch, speed, isStatic, pos, looping, priority, minDistance, maxDistance, targets, true);
    }

    private static int playSoundInternal(String id, ResourceLocation soundEventId, float volume, float pitch, float speed, boolean isStatic,
                                         Vec3 pos, boolean looping, int priority, float minDistance, float maxDistance,
                                         Collection<ServerPlayer> targets, boolean isStreamed) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                isStreamed ? SoundControlPacket.Action.STREAMED_PLAY : SoundControlPacket.Action.PLAY,
                id,
                soundEventId,
                volume, pitch, speed,
                isStatic,
                pos.x, pos.y, pos.z,
                looping,
                priority,
                minDistance,
                maxDistance
        );

        String message = "[GFBS Auralis] 已向 %d 名玩家发送播放指令: " + soundEventId + " (id=" + id + ")";
        if (isStreamed) {
            message = "[GFBS Auralis] 已向 %d 名玩家发送流式播放指令: " + soundEventId + " (id=" + id + ")";
        }

        return sendPacketToPlayers(packet, targets, message);
    }

    public static int pauseSound(String id, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.PAUSE,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家发送暂停指令 (id=" + id + ")");
    }

    public static int stopSound(String id, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.STOP,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家发送停止指令 (id=" + id + ")");
    }

    public static int setVolume(String id, float volume, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_VOLUME,
                id,
                new ResourceLocation("minecraft:empty"),
                volume, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家设置音量 (id=" + id + ", volume=" + volume + ")");
    }

    public static int setPitch(String id, float pitch, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_PITCH,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, pitch, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家设置音高 (id=" + id + ", pitch=" + pitch + ")");
    }

    public static int setPosition(String id, Vec3 pos, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_POSITION,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, 0f, 0f,
                false,
                pos.x, pos.y, pos.z,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家设置位置 (id=" + id + ", pos=" + pos.x + "," + pos.y + "," + pos.z + ")");
    }

    public static int setStatic(String id, boolean isStatic, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_STATIC,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, 0f, 0f,
                isStatic,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家设置静态模式 (id=" + id + ", static=" + isStatic + ")");
    }

    public static int setLooping(String id, boolean looping, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_LOOPING,
                id,
                new ResourceLocation("minecraft:empty"),
                0f, 0f, 0f,
                looping,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        return sendPacketToPlayers(packet, targets, "[GFBS Auralis] 已向 %d 名玩家设置循环 (id=" + id + ", looping=" + looping + ")");
    }

    public static int tween(String id, TweenControlPacket.Property property, double value, float duration, Collection<ServerPlayer> targets) {
        return tween(id, property, value, duration, EasingStyle.LINEAR, EasingDirection.OUT, targets);
    }

    public static int tween(String id, TweenControlPacket.Property property, double value, float duration,
                            EasingStyle style, EasingDirection dir, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        TweenControlPacket packet = new TweenControlPacket(
                property, id,
                value, 0d, 0d,
                duration,
                style, dir
        );

        return sendTweenPacket(packet, targets,
                "[GFBS Auralis] 已向 %d 名玩家发送渐变指令 (id=" + id + ", prop=" + property.name() + ", target=" + value + ", duration=" + duration + "s)");
    }

    public static int tweenPosition(String id, Vec3 targetPos, float duration, Collection<ServerPlayer> targets) {
        return tweenPosition(id, targetPos, duration, EasingStyle.LINEAR, EasingDirection.OUT, targets);
    }

    public static int tweenPosition(String id, Vec3 targetPos, float duration,
                                    EasingStyle style, EasingDirection dir, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        TweenControlPacket packet = new TweenControlPacket(
                TweenControlPacket.Property.POSITION, id,
                targetPos.x, targetPos.y, targetPos.z,
                duration,
                style, dir
        );

        return sendTweenPacket(packet, targets,
                "[GFBS Auralis] 已向 %d 名玩家发送位置渐变指令 (id=" + id + ", target=" + targetPos.x + "," + targetPos.y + "," + targetPos.z + ", duration=" + duration + "s)");
    }

    public static int bindEntity(String id, int entityId, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        BindControlPacket packet = BindControlPacket.bindEntity(id, entityId, new java.util.UUID(0, 0));
        return sendBindPacket(packet, targets);
    }

    public static int bindBlock(String id, BlockPos pos, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        BindControlPacket packet = BindControlPacket.bindBlock(id, pos);
        return sendBindPacket(packet, targets);
    }

    public static int unbind(String id, Collection<ServerPlayer> targets) {
        if (targets == null) return 0;

        BindControlPacket packet = BindControlPacket.unbind(id);
        return sendBindPacket(packet, targets);
    }

    private static int sendBindPacket(BindControlPacket packet, Collection<ServerPlayer> targets) {
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
        }
        return 1;
    }

    private static int sendTweenPacket(TweenControlPacket packet, Collection<ServerPlayer> targets, String message) {
        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        return 1;
    }

    private static int sendPacketToPlayers(SoundControlPacket packet, Collection<ServerPlayer> targets, String successMessage) {
        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;

        return 1;
    }
}
