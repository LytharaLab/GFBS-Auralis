package org.lytharalab.gfbs.auralis.network.sync;

import net.minecraft.network.FriendlyByteBuf;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.tween.Easing;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;

import java.util.Objects;

/** Immutable server-clock tween description used by late-join snapshots. */
public record SyncedTweenState(
        TweenControlPacket.Property property,
        double startX,
        double startY,
        double startZ,
        double targetX,
        double targetY,
        double targetZ,
        long startServerNanos,
        long durationNanos,
        EasingStyle easingStyle,
        EasingDirection easingDirection
) {
    private static final long MAX_DURATION_NANOS = 24L * 60L * 60L * 1_000_000_000L;

    public SyncedTweenState {
        property = Objects.requireNonNull(property, "property");
        easingStyle = Objects.requireNonNullElse(easingStyle, EasingStyle.LINEAR);
        easingDirection = Objects.requireNonNullElse(easingDirection, EasingDirection.OUT);
        startX = finite(startX);
        startY = finite(startY);
        startZ = finite(startZ);
        targetX = finite(targetX);
        targetY = finite(targetY);
        targetZ = finite(targetZ);
        durationNanos = Math.max(1L, Math.min(durationNanos, MAX_DURATION_NANOS));
    }

    public double alphaAt(long serverNowNanos) {
        if (serverNowNanos <= startServerNanos) return 0.0;
        double alpha = (serverNowNanos - startServerNanos) / (double) durationNanos;
        return Math.max(0.0, Math.min(1.0, alpha));
    }

    public double easedAlphaAt(long serverNowNanos) {
        return Easing.ease(easingStyle, easingDirection, alphaAt(serverNowNanos));
    }

    public double xAt(long serverNowNanos) {
        return interpolate(startX, targetX, easedAlphaAt(serverNowNanos));
    }

    public double yAt(long serverNowNanos) {
        return interpolate(startY, targetY, easedAlphaAt(serverNowNanos));
    }

    public double zAt(long serverNowNanos) {
        return interpolate(startZ, targetZ, easedAlphaAt(serverNowNanos));
    }

    public boolean isComplete(long serverNowNanos) {
        return serverNowNanos - startServerNanos >= durationNanos;
    }

    public long endServerNanos() {
        long end = startServerNanos + durationNanos;
        return end < startServerNanos ? Long.MAX_VALUE : end;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(property.ordinal());
        buffer.writeDouble(startX);
        buffer.writeDouble(startY);
        buffer.writeDouble(startZ);
        buffer.writeDouble(targetX);
        buffer.writeDouble(targetY);
        buffer.writeDouble(targetZ);
        buffer.writeLong(startServerNanos);
        buffer.writeVarLong(durationNanos);
        buffer.writeVarInt(easingStyle.ordinal());
        buffer.writeVarInt(easingDirection.ordinal());
    }

    public static SyncedTweenState decode(FriendlyByteBuf buffer) {
        TweenControlPacket.Property property = enumValue(
                TweenControlPacket.Property.values(), buffer.readVarInt(), "tween property"
        );
        double startX = buffer.readDouble();
        double startY = buffer.readDouble();
        double startZ = buffer.readDouble();
        double targetX = buffer.readDouble();
        double targetY = buffer.readDouble();
        double targetZ = buffer.readDouble();
        long startNanos = buffer.readLong();
        long durationNanos = buffer.readVarLong();
        EasingStyle style = enumValue(EasingStyle.values(), buffer.readVarInt(), "easing style");
        EasingDirection direction = enumValue(
                EasingDirection.values(), buffer.readVarInt(), "easing direction"
        );
        return new SyncedTweenState(
                property,
                startX, startY, startZ,
                targetX, targetY, targetZ,
                startNanos, durationNanos,
                style, direction
        );
    }

    private static double interpolate(double from, double to, double alpha) {
        return from + (to - from) * alpha;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static <T> T enumValue(T[] values, int ordinal, String label) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown Auralis " + label + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
