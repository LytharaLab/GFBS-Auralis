package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientAuthorityController;
import org.lytharalab.gfbs.auralis.api.AuthoritativeSoundSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record AuthoritativeSnapshotPacket(UUID snapshotId, int chunkIndex, int chunkCount,
                                          boolean full, List<AuthoritativeSoundSnapshot> sounds) {
    private static final int MAX_SOUNDS = 96;

    public AuthoritativeSnapshotPacket { sounds = List.copyOf(sounds); }

    public static void encode(AuthoritativeSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.snapshotId);
        buffer.writeVarInt(packet.chunkIndex);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeBoolean(packet.full);
        buffer.writeVarInt(packet.sounds.size());
        for (AuthoritativeSoundSnapshot sound : packet.sounds) AuthoritativePacketCodec.writeSnapshot(buffer, sound);
    }

    public static AuthoritativeSnapshotPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        boolean full = buffer.readBoolean();
        int size = buffer.readVarInt();
        if (count < 1 || count > 64 || index < 0 || index >= count || size < 0 || size > MAX_SOUNDS) {
            throw new IllegalArgumentException("Invalid authoritative snapshot bounds");
        }
        List<AuthoritativeSoundSnapshot> sounds = new ArrayList<>(size);
        for (int i = 0; i < size; i++) sounds.add(AuthoritativePacketCodec.readSnapshot(buffer));
        return new AuthoritativeSnapshotPacket(id, index, count, full, sounds);
    }

    public static void handle(AuthoritativeSnapshotPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAuthorityController.accept(packet)));
        context.setPacketHandled(true);
    }
}
