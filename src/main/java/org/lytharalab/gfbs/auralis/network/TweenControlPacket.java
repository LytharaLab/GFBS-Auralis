package org.lytharalab.gfbs.auralis.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.auralis.ClientSoundController;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;

import java.util.function.Supplier;

public class TweenControlPacket {

    public enum Property {
        VOLUME,
        PITCH,
        SPEED,
        POSITION,
        MIN_DISTANCE,
        MAX_DISTANCE
    }

    public final Property property;
    public final String id;
    public final double targetX;
    public final double targetY;
    public final double targetZ;
    public final float duration;
    public final EasingStyle easingStyle;
    public final EasingDirection easingDirection;

    public TweenControlPacket(Property property, String id,
                              double targetX, double targetY, double targetZ,
                              float duration,
                              EasingStyle easingStyle, EasingDirection easingDirection) {
        this.property = property;
        this.id = id;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.duration = duration;
        this.easingStyle = easingStyle;
        this.easingDirection = easingDirection;
    }

    public static void encode(TweenControlPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.property.ordinal());
        buf.writeUtf(pkt.id);
        buf.writeDouble(pkt.targetX);
        buf.writeDouble(pkt.targetY);
        buf.writeDouble(pkt.targetZ);
        buf.writeFloat(pkt.duration);
        buf.writeVarInt(pkt.easingStyle.ordinal());
        buf.writeVarInt(pkt.easingDirection.ordinal());
    }

    public static TweenControlPacket decode(FriendlyByteBuf buf) {
        int propOrdinal = buf.readVarInt();
        Property prop = Property.values()[Math.max(0, Math.min(propOrdinal, Property.values().length - 1))];
        String id = buf.readUtf();
        double tx = buf.readDouble();
        double ty = buf.readDouble();
        double tz = buf.readDouble();
        float duration = buf.readFloat();
        int styleOrdinal = buf.readVarInt();
        EasingStyle style = EasingStyle.values()[Math.max(0, Math.min(styleOrdinal, EasingStyle.values().length - 1))];
        int dirOrdinal = buf.readVarInt();
        EasingDirection dir = EasingDirection.values()[Math.max(0, Math.min(dirOrdinal, EasingDirection.values().length - 1))];
        return new TweenControlPacket(prop, id, tx, ty, tz, duration, style, dir);
    }

    public static void handle(TweenControlPacket pkt, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            if (!ctx.getDirection().getReceptionSide().isClient()) return;
            ClientSoundController.startTween(
                    pkt.property, pkt.id,
                    pkt.targetX, pkt.targetY, pkt.targetZ,
                    pkt.duration, pkt.easingStyle, pkt.easingDirection
            );
        });
        ctx.setPacketHandled(true);
    }
}
