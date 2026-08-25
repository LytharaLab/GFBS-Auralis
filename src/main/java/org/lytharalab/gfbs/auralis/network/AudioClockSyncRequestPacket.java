package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.function.Supplier;

/** Client clock probe; it never carries client-authoritative audio state. */
public record AudioClockSyncRequestPacket(
        long requestId,
        long clientSendNanos,
        boolean requestSnapshot
) {
    public static void encode(AudioClockSyncRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.requestId);
        buffer.writeLong(packet.clientSendNanos);
        buffer.writeBoolean(packet.requestSnapshot);
    }

    public static AudioClockSyncRequestPacket decode(FriendlyByteBuf buffer) {
        return new AudioClockSyncRequestPacket(
                buffer.readVarLong(),
                buffer.readLong(),
                buffer.readBoolean()
        );
    }

    public static void handle(
            AudioClockSyncRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        long serverReceiveNanos = System.nanoTime();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            long serverSendNanos = System.nanoTime();
            NetworkHandler.CHANNEL.sendTo(
                    new AudioClockSyncResponsePacket(
                            packet.requestId,
                            packet.clientSendNanos,
                            serverReceiveNanos,
                            serverSendNanos
                    ),
                    sender.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
            if (packet.requestSnapshot) {
                AuralisServerManager.requestSnapshotFromClient(sender.getUUID());
            }
        }
        context.setPacketHandled(true);
    }
}
