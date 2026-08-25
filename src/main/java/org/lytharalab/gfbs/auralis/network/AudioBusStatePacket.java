package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.network.sync.SyncedBusState;
import org.lytharalab.gfbs.auralis.sync.AudioSyncClient;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Idempotent final state for one remotely controlled audio bus. */
public record AudioBusStatePacket(
        UUID serverEpoch,
        SyncedBusState state,
        long serverSendNanos
) {
    public AudioBusStatePacket {
        serverEpoch = Objects.requireNonNull(serverEpoch, "serverEpoch");
        state = Objects.requireNonNull(state, "state");
    }

    public static void encode(AudioBusStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.serverEpoch);
        packet.state.encode(buffer);
        buffer.writeLong(packet.serverSendNanos);
    }

    public static AudioBusStatePacket decode(FriendlyByteBuf buffer) {
        return new AudioBusStatePacket(
                buffer.readUUID(),
                SyncedBusState.decode(buffer),
                buffer.readLong()
        );
    }

    public static void handle(
            AudioBusStatePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> AudioSyncClient.acceptBusDelta(packet));
        }
        context.setPacketHandled(true);
    }
}
