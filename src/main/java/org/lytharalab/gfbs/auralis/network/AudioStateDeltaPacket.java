package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.network.sync.SyncedSoundState;
import org.lytharalab.gfbs.auralis.sync.AudioSyncClient;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Ordered, idempotent full-state delta for one authoritative sound instance. */
public record AudioStateDeltaPacket(
        UUID serverEpoch,
        Action action,
        long revision,
        String soundId,
        long serverSendNanos,
        SyncedSoundState state
) {
    public enum Action { UPSERT, REMOVE }

    public AudioStateDeltaPacket {
        serverEpoch = Objects.requireNonNull(serverEpoch, "serverEpoch");
        action = Objects.requireNonNull(action, "action");
        soundId = Objects.requireNonNull(soundId, "soundId");
        if (action == Action.UPSERT) Objects.requireNonNull(state, "state");
    }

    public static AudioStateDeltaPacket upsert(UUID epoch, SyncedSoundState state, long serverSendNanos) {
        return new AudioStateDeltaPacket(
                epoch, Action.UPSERT, state.revision(), state.id(), serverSendNanos, state
        );
    }

    public static AudioStateDeltaPacket remove(
            UUID epoch,
            String soundId,
            long revision,
            long serverSendNanos
    ) {
        return new AudioStateDeltaPacket(epoch, Action.REMOVE, revision, soundId, serverSendNanos, null);
    }

    public static void encode(AudioStateDeltaPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.serverEpoch);
        buffer.writeVarInt(packet.action.ordinal());
        buffer.writeVarLong(packet.revision);
        buffer.writeUtf(packet.soundId, SyncedSoundState.MAX_ID_LENGTH);
        buffer.writeLong(packet.serverSendNanos);
        if (packet.action == Action.UPSERT) packet.state.encode(buffer);
    }

    public static AudioStateDeltaPacket decode(FriendlyByteBuf buffer) {
        UUID epoch = buffer.readUUID();
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= Action.values().length) {
            throw new IllegalArgumentException("Unknown Auralis sound delta action: " + ordinal);
        }
        Action action = Action.values()[ordinal];
        long revision = buffer.readVarLong();
        String id = buffer.readUtf(SyncedSoundState.MAX_ID_LENGTH);
        long serverSendNanos = buffer.readLong();
        SyncedSoundState state = action == Action.UPSERT ? SyncedSoundState.decode(buffer) : null;
        if (state != null && (!state.id().equals(id) || state.revision() != revision)) {
            throw new IllegalArgumentException("Inconsistent Auralis sound delta envelope");
        }
        return new AudioStateDeltaPacket(epoch, action, revision, id, serverSendNanos, state);
    }

    public static void handle(
            AudioStateDeltaPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> AudioSyncClient.acceptSoundDelta(packet));
        }
        context.setPacketHandled(true);
    }
}
