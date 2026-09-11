package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.api.AuralisClientApi;
import org.lytharalab.gfbs.auralis.api.ClientRequestResult;

import java.util.UUID;
import java.util.function.Supplier;

public record ClientRequestResultPacket(UUID requestId, boolean applied, String detail) {
    public ClientRequestResultPacket { detail = detail == null ? "" : detail; }
    public static void encode(ClientRequestResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId); buffer.writeBoolean(packet.applied); buffer.writeUtf(packet.detail, 256);
    }
    public static ClientRequestResultPacket decode(FriendlyByteBuf buffer) {
        return new ClientRequestResultPacket(buffer.readUUID(), buffer.readBoolean(), buffer.readUtf(256));
    }
    public static void handle(ClientRequestResultPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AuralisClientApi.accept(new ClientRequestResult(packet.requestId, packet.applied, packet.detail))));
        context.setPacketHandled(true);
    }
}
