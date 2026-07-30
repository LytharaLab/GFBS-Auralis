package org.lytharalab.gfbs.auralis.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientSoundController;

import java.util.UUID;
import java.util.function.Supplier;

public class BindControlPacket {

    public enum Action {
        BIND_ENTITY,
        BIND_BLOCK,
        UNBIND
    }

    public final Action action;
    public final String soundId;
    public final int entityId;
    public final UUID entityUuid;
    public final BlockPos blockPos;

    private BindControlPacket(Action action, String soundId, int entityId, UUID entityUuid, BlockPos blockPos) {
        this.action = action;
        this.soundId = soundId;
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.blockPos = blockPos;
    }

    public static BindControlPacket bindEntity(String soundId, int entityId, UUID entityUuid) {
        return new BindControlPacket(Action.BIND_ENTITY, soundId, entityId, entityUuid, BlockPos.ZERO);
    }

    public static BindControlPacket bindBlock(String soundId, BlockPos pos) {
        return new BindControlPacket(Action.BIND_BLOCK, soundId, 0, new UUID(0, 0), pos);
    }

    public static BindControlPacket unbind(String soundId) {
        return new BindControlPacket(Action.UNBIND, soundId, 0, new UUID(0, 0), BlockPos.ZERO);
    }

    public static void encode(BindControlPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.action.ordinal());
        buf.writeUtf(pkt.soundId);
        buf.writeVarInt(pkt.entityId);
        buf.writeUUID(pkt.entityUuid);
        buf.writeBlockPos(pkt.blockPos);
    }

    public static BindControlPacket decode(FriendlyByteBuf buf) {
        int actionOrdinal = buf.readVarInt();
        Action action = Action.values()[Math.max(0, Math.min(actionOrdinal, Action.values().length - 1))];
        String soundId = buf.readUtf();
        int entityId = buf.readVarInt();
        UUID entityUuid = buf.readUUID();
        BlockPos pos = buf.readBlockPos();
        return new BindControlPacket(action, soundId, entityId, entityUuid, pos);
    }

    public static void handle(BindControlPacket pkt, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            if (!ctx.getDirection().getReceptionSide().isClient()) return;
            switch (pkt.action) {
                case BIND_ENTITY -> ClientSoundController.bindEntity(pkt.soundId, pkt.entityId, pkt.entityUuid);
                case BIND_BLOCK -> ClientSoundController.bindBlock(pkt.soundId, pkt.blockPos);
                case UNBIND -> ClientSoundController.unbindSound(pkt.soundId);
            }
        });
        ctx.setPacketHandled(true);
    }
}
