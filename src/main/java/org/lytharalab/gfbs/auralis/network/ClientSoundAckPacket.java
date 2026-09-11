package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.api.ClientExecutionStatus;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.UUID;
import java.util.function.Supplier;

public record ClientSoundAckPacket(UUID operationId, String soundId, long epoch, long revision,
                                   ClientExecutionStatus status, String detail, double durationSeconds) {
    public ClientSoundAckPacket {
        if (operationId == null || soundId == null || status == null) throw new IllegalArgumentException("Missing ACK field");
        if (soundId.length() > AuthoritativePacketCodec.MAX_ID) throw new IllegalArgumentException("Sound id too long");
        detail = detail == null ? "" : detail;
        if (detail.length() > 256) detail = detail.substring(0, 256);
    }

    public static void encode(ClientSoundAckPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.operationId);
        buffer.writeUtf(packet.soundId, AuthoritativePacketCodec.MAX_ID);
        buffer.writeLong(packet.epoch);
        buffer.writeLong(packet.revision);
        buffer.writeVarInt(packet.status.ordinal());
        buffer.writeUtf(packet.detail, 256);
        buffer.writeDouble(packet.durationSeconds);
    }

    public static ClientSoundAckPacket decode(FriendlyByteBuf buffer) {
        UUID operationId = buffer.readUUID();
        String soundId = buffer.readUtf(AuthoritativePacketCodec.MAX_ID);
        long epoch = buffer.readLong();
        long revision = buffer.readLong();
        int ordinal = buffer.readVarInt();
        ClientExecutionStatus[] values = ClientExecutionStatus.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid ACK status");
        return new ClientSoundAckPacket(operationId, soundId, epoch, revision, values[ordinal],
                buffer.readUtf(256), buffer.readDouble());
    }

    public static void handle(ClientSoundAckPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> AuralisServerManager.acknowledge(sender, packet));
        context.setPacketHandled(true);
    }
}
