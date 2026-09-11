package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientAuthorityController;
import org.lytharalab.gfbs.auralis.api.AuthoritativeSoundSnapshot;

import java.util.UUID;
import java.util.function.Supplier;

public record AuthoritativeSoundStatePacket(UUID operationId, AuthoritativeSoundSnapshot snapshot) {
    public static void encode(AuthoritativeSoundStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.operationId);
        AuthoritativePacketCodec.writeSnapshot(buffer, packet.snapshot);
    }

    public static AuthoritativeSoundStatePacket decode(FriendlyByteBuf buffer) {
        return new AuthoritativeSoundStatePacket(buffer.readUUID(), AuthoritativePacketCodec.readSnapshot(buffer));
    }

    public static void handle(AuthoritativeSoundStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAuthorityController.accept(packet)));
        context.setPacketHandled(true);
    }
}
