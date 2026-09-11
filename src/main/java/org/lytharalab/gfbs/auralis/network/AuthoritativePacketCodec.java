package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.AuralisPlaybackState;
import org.lytharalab.gfbs.auralis.api.AuralisSoundSpec;
import org.lytharalab.gfbs.auralis.api.AuthoritativeSoundSnapshot;

final class AuthoritativePacketCodec {
    static final int MAX_ID = 128;
    static final int MAX_BUS = 128;

    private AuthoritativePacketCodec() { }

    static void writeSpec(FriendlyByteBuf buffer, AuralisSoundSpec spec) {
        buffer.writeResourceLocation(spec.soundEventId());
        buffer.writeBoolean(spec.streamed());
        buffer.writeFloat(spec.volume());
        buffer.writeFloat(spec.pitch());
        buffer.writeFloat(spec.speed());
        buffer.writeBoolean(spec.listenerRelative());
        buffer.writeDouble(spec.position().x);
        buffer.writeDouble(spec.position().y);
        buffer.writeDouble(spec.position().z);
        buffer.writeBoolean(spec.looping());
        buffer.writeVarInt(spec.priority());
        buffer.writeFloat(spec.minDistance());
        buffer.writeFloat(spec.maxDistance());
        buffer.writeUtf(spec.bus(), MAX_BUS);
    }

    static AuralisSoundSpec readSpec(FriendlyByteBuf buffer) {
        return new AuralisSoundSpec(buffer.readResourceLocation(), buffer.readBoolean(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readBoolean(), buffer.readVarInt(), buffer.readFloat(), buffer.readFloat(),
                buffer.readUtf(MAX_BUS));
    }

    static void writeSnapshot(FriendlyByteBuf buffer, AuthoritativeSoundSnapshot snapshot) {
        buffer.writeUtf(snapshot.id(), MAX_ID);
        buffer.writeLong(snapshot.epoch());
        buffer.writeLong(snapshot.revision());
        writeSpec(buffer, snapshot.spec());
        buffer.writeVarInt(snapshot.state().ordinal());
        buffer.writeLong(snapshot.anchorServerTick());
        buffer.writeDouble(snapshot.anchorPositionSeconds());
        buffer.writeDouble(snapshot.durationSeconds());
        buffer.writeBoolean(snapshot.global());
    }

    static AuthoritativeSoundSnapshot readSnapshot(FriendlyByteBuf buffer) {
        String id = buffer.readUtf(MAX_ID);
        long epoch = buffer.readLong();
        long revision = buffer.readLong();
        AuralisSoundSpec spec = readSpec(buffer);
        int ordinal = buffer.readVarInt();
        AuralisPlaybackState[] states = AuralisPlaybackState.values();
        if (ordinal < 0 || ordinal >= states.length) throw new IllegalArgumentException("Invalid playback state");
        return new AuthoritativeSoundSnapshot(id, epoch, revision, spec, states[ordinal], buffer.readLong(),
                buffer.readDouble(), buffer.readDouble(), buffer.readBoolean());
    }
}
