package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.function.Supplier;

public record ClientTimeProbePacket(long nonce, long clientSendNanos) {
    public static void encode(ClientTimeProbePacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.nonce); buffer.writeLong(packet.clientSendNanos);
    }
    public static ClientTimeProbePacket decode(FriendlyByteBuf buffer) {
        return new ClientTimeProbePacket(buffer.readLong(), buffer.readLong());
    }
    public static void handle(ClientTimeProbePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> NetworkHandler.CHANNEL.sendTo(
                new ServerTimeSyncPacket(packet.nonce, packet.clientSendNanos, AuralisServerManager.serviceTick(sender.server)),
                sender.connection.connection, NetworkDirection.PLAY_TO_CLIENT));
        context.setPacketHandled(true);
    }
}
