package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.sync.AudioSyncClient;

import java.util.function.Supplier;

/** Four-timestamp monotonic-clock response (NTP-style offset estimation). */
public record AudioClockSyncResponsePacket(
        long requestId,
        long clientSendNanos,
        long serverReceiveNanos,
        long serverSendNanos
) {
    public static void encode(AudioClockSyncResponsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.requestId);
        buffer.writeLong(packet.clientSendNanos);
        buffer.writeLong(packet.serverReceiveNanos);
        buffer.writeLong(packet.serverSendNanos);
    }

    public static AudioClockSyncResponsePacket decode(FriendlyByteBuf buffer) {
        return new AudioClockSyncResponsePacket(
                buffer.readVarLong(),
                buffer.readLong(),
                buffer.readLong(),
                buffer.readLong()
        );
    }

    public static void handle(
            AudioClockSyncResponsePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            // Capture receive time on the Netty thread; a main-thread queue delay
            // would otherwise contaminate the round-trip estimate.
            AudioSyncClient.acceptClockResponse(packet, System.nanoTime());
        }
        context.setPacketHandled(true);
    }
}
