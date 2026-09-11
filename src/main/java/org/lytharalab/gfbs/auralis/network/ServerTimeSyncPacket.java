package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientAuthorityController;

import java.util.function.Supplier;

public record ServerTimeSyncPacket(long nonce, long clientSendNanos, long serverTick) {
    public static void encode(ServerTimeSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.nonce); buffer.writeLong(packet.clientSendNanos); buffer.writeLong(packet.serverTick);
    }
    public static ServerTimeSyncPacket decode(FriendlyByteBuf buffer) {
        return new ServerTimeSyncPacket(buffer.readLong(), buffer.readLong(), buffer.readLong());
    }
    public static void handle(ServerTimeSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAuthorityController.accept(packet, System.nanoTime())));
        context.setPacketHandled(true);
    }
}
