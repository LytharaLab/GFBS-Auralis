package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.lytharalab.gfbs.auralis.GFBsAuralisConfig;
import org.lytharalab.gfbs.auralis.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public record ClientSoundRequestPacket(UUID requestId, Action action, String id,
                                       @Nullable AuralisSoundSpec spec, double positionSeconds) {
    public enum Action { CREATE, PLAY, PAUSE, STOP, SEEK, UPDATE, DISPOSE }

    public ClientSoundRequestPacket {
        if (requestId == null || action == null || id == null || id.isBlank() || id.length() > 80
                || !id.matches("[a-zA-Z0-9_.:/-]+")) throw new IllegalArgumentException("Invalid client sound request");
        if ((action == Action.CREATE || action == Action.UPDATE) != (spec != null)) throw new IllegalArgumentException("Invalid request spec");
        if (action == Action.SEEK && (!Double.isFinite(positionSeconds) || positionSeconds < 0.0)) throw new IllegalArgumentException("Invalid seek");
    }

    public static void encode(ClientSoundRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId); buffer.writeVarInt(packet.action.ordinal());
        buffer.writeUtf(packet.id, 80); buffer.writeBoolean(packet.spec != null);
        if (packet.spec != null) AuthoritativePacketCodec.writeSpec(buffer, packet.spec);
        buffer.writeDouble(packet.positionSeconds);
    }

    public static ClientSoundRequestPacket decode(FriendlyByteBuf buffer) {
        UUID requestId = buffer.readUUID();
        int ordinal = buffer.readVarInt();
        Action[] actions = Action.values();
        if (ordinal < 0 || ordinal >= actions.length) throw new IllegalArgumentException("Invalid request action");
        String id = buffer.readUtf(80);
        AuralisSoundSpec spec = buffer.readBoolean() ? AuthoritativePacketCodec.readSpec(buffer) : null;
        return new ClientSoundRequestPacket(requestId, actions[ordinal], id, spec, buffer.readDouble());
    }

    public static void handle(ClientSoundRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> execute(sender, packet));
        context.setPacketHandled(true);
    }

    private static void execute(ServerPlayer sender, ClientSoundRequestPacket request) {
        if (!GFBsAuralisConfig.SERVER.allowClientRequests.get()) {
            reply(sender, request.requestId, false, "Client Auralis requests are disabled");
            return;
        }
        String serverId = "client." + sender.getUUID() + "." + request.id;
        try {
            CompletionStage<Boolean> execution;
            if (request.action == Action.CREATE) {
                execution = AuralisServerApi.create(sender.server, serverId, request.spec, List.of(sender)).future()
                        .thenApply(ignored -> true);
            } else {
                ServerSoundInstance instance = AuralisServerApi.find(sender.server, serverId)
                        .orElseThrow(() -> new IllegalStateException("Unknown client-owned sound: " + request.id));
                AuralisOperation<ServerOperationResult> operation = switch (request.action) {
                    case PLAY -> instance.play();
                    case PAUSE -> instance.pause();
                    case STOP -> instance.stop();
                    case SEEK -> instance.seek(request.positionSeconds);
                    case UPDATE -> instance.update(request.spec);
                    case DISPOSE -> instance.dispose();
                    case CREATE -> throw new IllegalStateException("unreachable");
                };
                execution = operation.future().thenApply(ServerOperationResult::allApplied);
            }
            execution.whenComplete((applied, failure) -> reply(sender, request.requestId,
                    failure == null && Boolean.TRUE.equals(applied), failure == null
                            ? (Boolean.TRUE.equals(applied) ? "" : "One or more clients rejected the operation")
                            : String.valueOf(failure.getMessage())));
        } catch (Throwable failure) {
            reply(sender, request.requestId, false, String.valueOf(failure.getMessage()));
        }
    }

    private static void reply(ServerPlayer player, UUID requestId, boolean applied, String detail) {
        NetworkHandler.CHANNEL.sendTo(new ClientRequestResultPacket(requestId, applied, detail),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
