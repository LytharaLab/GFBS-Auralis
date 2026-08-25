package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.network.sync.SyncedBusState;
import org.lytharalab.gfbs.auralis.network.sync.SyncedSoundState;
import org.lytharalab.gfbs.auralis.sync.AudioSyncClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One bounded chunk of an atomic late-join audio snapshot. */
public record AudioStateSnapshotPacket(
        UUID serverEpoch,
        long snapshotId,
        long snapshotRevision,
        long serverSendNanos,
        int chunkIndex,
        int chunkCount,
        List<SyncedBusState> buses,
        List<SyncedSoundState> sounds
) {
    public static final int MAX_ENTRIES_PER_CHUNK = 32;
    public static final int MAX_CHUNKS = 256;

    public AudioStateSnapshotPacket {
        serverEpoch = Objects.requireNonNull(serverEpoch, "serverEpoch");
        buses = List.copyOf(Objects.requireNonNull(buses, "buses"));
        sounds = List.copyOf(Objects.requireNonNull(sounds, "sounds"));
        if (chunkCount < 1 || chunkCount > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Invalid Auralis snapshot chunk " + chunkIndex + "/" + chunkCount);
        }
        if (buses.size() + sounds.size() > MAX_ENTRIES_PER_CHUNK) {
            throw new IllegalArgumentException("Auralis snapshot chunk is too large");
        }
    }

    public static void encode(AudioStateSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.serverEpoch);
        buffer.writeVarLong(packet.snapshotId);
        buffer.writeVarLong(packet.snapshotRevision);
        buffer.writeLong(packet.serverSendNanos);
        buffer.writeVarInt(packet.chunkIndex);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeVarInt(packet.buses.size());
        for (SyncedBusState bus : packet.buses) bus.encode(buffer);
        buffer.writeVarInt(packet.sounds.size());
        for (SyncedSoundState sound : packet.sounds) sound.encode(buffer);
    }

    public static AudioStateSnapshotPacket decode(FriendlyByteBuf buffer) {
        UUID epoch = buffer.readUUID();
        long snapshotId = buffer.readVarLong();
        long snapshotRevision = buffer.readVarLong();
        long serverSendNanos = buffer.readLong();
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        int busCount = boundedCount(buffer.readVarInt(), "bus");
        List<SyncedBusState> buses = new ArrayList<>(busCount);
        for (int index = 0; index < busCount; index++) buses.add(SyncedBusState.decode(buffer));
        int soundCount = boundedCount(buffer.readVarInt(), "sound");
        if (busCount + soundCount > MAX_ENTRIES_PER_CHUNK) {
            throw new IllegalArgumentException("Auralis snapshot chunk exceeds its entry limit");
        }
        List<SyncedSoundState> sounds = new ArrayList<>(soundCount);
        for (int index = 0; index < soundCount; index++) sounds.add(SyncedSoundState.decode(buffer));
        return new AudioStateSnapshotPacket(
                epoch, snapshotId, snapshotRevision, serverSendNanos,
                chunkIndex, chunkCount, buses, sounds
        );
    }

    public static void handle(
            AudioStateSnapshotPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> AudioSyncClient.acceptSnapshotChunk(packet));
        }
        context.setPacketHandled(true);
    }

    private static int boundedCount(int count, String label) {
        if (count < 0 || count > MAX_ENTRIES_PER_CHUNK) {
            throw new IllegalArgumentException("Invalid Auralis snapshot " + label + " count: " + count);
        }
        return count;
    }
}
