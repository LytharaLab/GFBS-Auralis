package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientSoundController;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;

import java.util.function.Supplier;

/** Bounded server-to-client real-time bus and instance-routing control. */
public record BusControlPacket(Action action, String target, String parent, float value, boolean flag) {
    public enum Action {
        SET_INSTANCE_BUS,
        CREATE_BUS,
        REMOVE_BUS,
        SET_PARENT,
        SET_VOLUME,
        SET_MUTED,
        SET_SOLO,
        SET_EFFECTS_BYPASSED
    }

    private static final int MAX_NAME = 96;

    public BusControlPacket {
        if (action == null) action = Action.SET_INSTANCE_BUS;
        target = target == null ? "" : target;
        parent = parent == null ? AudioBusSystem.MASTER : parent;
    }

    public static void encode(BusControlPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.action.ordinal());
        buffer.writeUtf(packet.target, MAX_NAME);
        buffer.writeUtf(packet.parent, MAX_NAME);
        buffer.writeFloat(packet.value);
        buffer.writeBoolean(packet.flag);
    }

    public static BusControlPacket decode(FriendlyByteBuf buffer) {
        int ordinal = buffer.readVarInt();
        Action[] values = Action.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown Auralis bus action ordinal: " + ordinal);
        }
        Action action = values[ordinal];
        return new BusControlPacket(
                action,
                buffer.readUtf(MAX_NAME),
                buffer.readUtf(MAX_NAME),
                buffer.readFloat(),
                buffer.readBoolean()
        );
    }

    public static void handle(BusControlPacket packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (!ctx.getDirection().getReceptionSide().isClient()) return;
            try {
                if (packet.action == Action.SET_INSTANCE_BUS) {
                    ClientSoundController.setBus(packet.target, packet.parent);
                    return;
                }
                if (!AuralisApi.isInitialized()) return;
                var buses = AuralisApi.buses();
                switch (packet.action) {
                    case CREATE_BUS -> {
                        if (buses.findBus(packet.target).isEmpty()) buses.createBus(packet.target, packet.parent);
                    }
                    case REMOVE_BUS -> buses.removeBus(packet.target);
                    case SET_PARENT -> buses.requireBus(packet.target).setParent(packet.parent);
                    case SET_VOLUME -> buses.requireBus(packet.target).setVolume(packet.value);
                    case SET_MUTED -> buses.requireBus(packet.target).setMuted(packet.flag);
                    case SET_SOLO -> buses.requireBus(packet.target).setSolo(packet.flag);
                    case SET_EFFECTS_BYPASSED -> buses.requireBus(packet.target).setEffectsBypassed(packet.flag);
                    case SET_INSTANCE_BUS -> { }
                }
            } catch (Throwable failure) {
                GFBsAuralis.LOGGER.warn("Rejected invalid remote Auralis bus operation {} for {}", packet.action, packet.target, failure);
            }
        });
        ctx.setPacketHandled(true);
    }
}
