package org.lytharalab.gfbs.auralis.network;

/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 *
 * Client-bound sound control packet.
 *
 * This packet is designed to be sent from server -> client.
 * The client side applies the action using {@link org.lytharalab.gfbs.auralis.ClientSoundController}.
 */

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientSoundController;

import java.util.function.Supplier;

public class SoundControlPacket {
    public enum Action {
        PLAY,
        STREAMED_PLAY,
        PAUSE,
        STOP,
        SET_VOLUME,
        SET_PITCH,
        SET_SPEED,
        SET_POSITION,
        SET_STATIC,
        SET_LOOPING,
        SET_PRIORITY,
        SET_MIN_DISTANCE,
        SET_MAX_DISTANCE
    }

    public final Action action;
    public final String id;

    // For PLAY
    public final ResourceLocation soundEventId;
    public final float volume;
    public final float pitch;
    public final float speed;
    public final boolean isStatic;
    public final double x;
    public final double y;
    public final double z;
    public final boolean looping;
    public final int priority;
    public final float minDistance;
    public final float maxDistance;

    public SoundControlPacket(
            Action action,
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            double x,
            double y,
            double z,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance
    ) {
        this.action = action;
        this.id = id;
        this.soundEventId = soundEventId;
        this.volume = volume;
        this.pitch = pitch;
        this.speed = speed;
        this.isStatic = isStatic;
        this.x = x;
        this.y = y;
        this.z = z;
        this.looping = looping;
        this.priority = priority;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
    }

    public static void encode(SoundControlPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.action.ordinal());
        buffer.writeUtf(packet.id);

        // Keep encoding fixed-size to keep code simple and robust.
        buffer.writeResourceLocation(packet.soundEventId);
        buffer.writeFloat(packet.volume);
        buffer.writeFloat(packet.pitch);
        buffer.writeFloat(packet.speed);
        buffer.writeBoolean(packet.isStatic);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeBoolean(packet.looping);
        buffer.writeVarInt(packet.priority);
        buffer.writeFloat(packet.minDistance);
        buffer.writeFloat(packet.maxDistance);
    }

    public static SoundControlPacket decode(FriendlyByteBuf buffer) {
        int actionOrdinal = buffer.readVarInt();
        Action action = Action.values()[Math.max(0, Math.min(actionOrdinal, Action.values().length - 1))];
        String id = buffer.readUtf();

        ResourceLocation soundEventId = buffer.readResourceLocation();
        float volume = buffer.readFloat();
        float pitch = buffer.readFloat();
        float speed = buffer.readFloat();
        boolean isStatic = buffer.readBoolean();
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        boolean looping = buffer.readBoolean();
        int priority = buffer.readVarInt();
        float minDistance = buffer.readFloat();
        float maxDistance = buffer.readFloat();

        return new SoundControlPacket(action, id, soundEventId, volume, pitch, speed, isStatic, x, y, z, looping, priority, minDistance, maxDistance);
    }

    public static void handle(SoundControlPacket packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            // Only meaningful on client reception. On server, ignore.
            if (!ctx.getDirection().getReceptionSide().isClient()) {
                return;
            }

            switch (packet.action) {
                case PLAY -> ClientSoundController.play(
                        packet.id,
                        packet.soundEventId,
                        packet.volume,
                        packet.pitch,
                        packet.speed,
                        packet.isStatic,
                        new Vec3(packet.x, packet.y, packet.z),
                        packet.looping,
                        packet.priority,
                        packet.minDistance,
                        packet.maxDistance,
                        false
                );
                case STREAMED_PLAY -> ClientSoundController.play(
                        packet.id,
                        packet.soundEventId,
                        packet.volume,
                        packet.pitch,
                        packet.speed,
                        packet.isStatic,
                        new Vec3(packet.x, packet.y, packet.z),
                        packet.looping,
                        packet.priority,
                        packet.minDistance,
                        packet.maxDistance,
                        true
                );
                case PAUSE -> ClientSoundController.pause(packet.id);
                case STOP -> ClientSoundController.stop(packet.id);
                case SET_VOLUME -> ClientSoundController.setVolume(packet.id, packet.volume);
                case SET_PITCH -> ClientSoundController.setPitch(packet.id, packet.pitch);
                case SET_SPEED -> ClientSoundController.setSpeed(packet.id, packet.speed);
                case SET_POSITION -> ClientSoundController.setPosition(packet.id, new Vec3(packet.x, packet.y, packet.z));
                case SET_STATIC -> ClientSoundController.setStatic(packet.id, packet.isStatic);
                case SET_LOOPING -> ClientSoundController.setLooping(packet.id, packet.looping);
                case SET_PRIORITY -> ClientSoundController.setPriority(packet.id, packet.priority);
                case SET_MIN_DISTANCE -> ClientSoundController.setMinDistance(packet.id, packet.minDistance);
                case SET_MAX_DISTANCE -> ClientSoundController.setMaxDistance(packet.id, packet.maxDistance);
            }
        });
        ctx.setPacketHandled(true);
    }
}
