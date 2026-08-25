package org.lytharalab.gfbs.auralis.network.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Complete immutable state of one server-authoritative logical sound instance.
 * The timeline is represented by a media cursor plus a server-monotonic anchor;
 * it therefore needs no per-instance ticking thread.
 */
public record SyncedSoundState(
        long revision,
        String id,
        ResourceLocation soundEventId,
        boolean streamed,
        PlaybackStatus playbackStatus,
        float volume,
        float pitch,
        float speed,
        boolean staticSound,
        double x,
        double y,
        double z,
        boolean looping,
        int priority,
        float minDistance,
        float maxDistance,
        String busName,
        BindingKind bindingKind,
        int entityId,
        UUID entityUuid,
        BlockPos blockPos,
        double durationSeconds,
        double playbackPositionSeconds,
        long playbackAnchorServerNanos,
        List<SyncedTweenState> tweens
) {
    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_BUS_NAME_LENGTH = 96;
    public static final int MAX_TWEENS = TweenControlPacket.Property.values().length;
    private static final double MAX_MEDIA_SECONDS = 365.0 * 24.0 * 60.0 * 60.0;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public enum PlaybackStatus { PLAYING, PAUSED }
    public enum BindingKind { NONE, ENTITY, BLOCK }

    public SyncedSoundState {
        if (revision < 0L) revision = 0L;
        id = bounded(Objects.requireNonNull(id, "id"), MAX_ID_LENGTH, "sound id");
        soundEventId = Objects.requireNonNull(soundEventId, "soundEventId");
        playbackStatus = Objects.requireNonNullElse(playbackStatus, PlaybackStatus.PLAYING);
        volume = clampFinite(volume, 0.0f, 16.0f, 1.0f);
        pitch = clampFinite(pitch, 0.01f, 8.0f, 1.0f);
        speed = clampFinite(speed, 0.01f, 8.0f, 1.0f);
        x = finite(x);
        y = finite(y);
        z = finite(z);
        priority = Math.max(0, Math.min(100, priority));
        minDistance = clampFinite(minDistance, 0.01f, 1_000_000.0f, 1.0f);
        maxDistance = clampFinite(maxDistance, minDistance, 1_000_000.0f, Math.max(48.0f, minDistance));
        busName = bounded(
                Objects.requireNonNullElse(busName, AudioBusSystem.MASTER).trim(),
                MAX_BUS_NAME_LENGTH,
                "bus name"
        );
        bindingKind = Objects.requireNonNullElse(bindingKind, BindingKind.NONE);
        entityUuid = Objects.requireNonNullElse(entityUuid, ZERO_UUID);
        blockPos = Objects.requireNonNullElse(blockPos, BlockPos.ZERO);
        durationSeconds = clampFinite(durationSeconds, 0.0, MAX_MEDIA_SECONDS, 0.0);
        playbackPositionSeconds = clampFinite(playbackPositionSeconds, 0.0, MAX_MEDIA_SECONDS, 0.0);
        List<SyncedTweenState> safeTweens = tweens == null ? List.of() : tweens.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(tween -> tween.property().ordinal()))
                .limit(MAX_TWEENS)
                .toList();
        tweens = List.copyOf(safeTweens);
    }

    public Vec3 positionAt(long serverNowNanos) {
        SyncedTweenState tween = tween(TweenControlPacket.Property.POSITION);
        return tween == null
                ? new Vec3(x, y, z)
                : new Vec3(tween.xAt(serverNowNanos), tween.yAt(serverNowNanos), tween.zAt(serverNowNanos));
    }

    public float volumeAt(long serverNowNanos) {
        return scalarAt(TweenControlPacket.Property.VOLUME, volume, serverNowNanos, 0.0f, 16.0f);
    }

    public float pitchAt(long serverNowNanos) {
        return scalarAt(TweenControlPacket.Property.PITCH, pitch, serverNowNanos, 0.01f, 8.0f);
    }

    public float speedAt(long serverNowNanos) {
        return scalarAt(TweenControlPacket.Property.SPEED, speed, serverNowNanos, 0.01f, 8.0f);
    }

    public float minDistanceAt(long serverNowNanos) {
        return scalarAt(TweenControlPacket.Property.MIN_DISTANCE, minDistance, serverNowNanos, 0.01f, 1_000_000.0f);
    }

    public float maxDistanceAt(long serverNowNanos) {
        float minimum = minDistanceAt(serverNowNanos);
        return scalarAt(TweenControlPacket.Property.MAX_DISTANCE, maxDistance, serverNowNanos, minimum, 1_000_000.0f);
    }

    public double playbackPositionAt(long serverNowNanos) {
        double position = playbackPositionSeconds;
        if (playbackStatus != PlaybackStatus.PLAYING || serverNowNanos <= playbackAnchorServerNanos) {
            return normalizePosition(position);
        }

        position += integratePlaybackRate(playbackAnchorServerNanos, serverNowNanos);
        return normalizePosition(position);
    }

    public boolean hasActiveTweens(long serverNowNanos) {
        return tweens.stream().anyMatch(tween -> !tween.isComplete(serverNowNanos));
    }

    public boolean hasActiveRateTween(long serverNowNanos) {
        return tweens.stream().anyMatch(tween ->
                (tween.property() == TweenControlPacket.Property.PITCH
                        || tween.property() == TweenControlPacket.Property.SPEED)
                        && !tween.isComplete(serverNowNanos)
        );
    }

    public boolean isKnownComplete(long serverNowNanos) {
        return !looping && durationSeconds > 0.0
                && playbackPositionAt(serverNowNanos) >= durationSeconds - 0.000_001;
    }

    /** Resolve the cursor/properties at {@code now} and discard completed tweens. */
    public SyncedSoundState materialize(long serverNowNanos) {
        Vec3 position = positionAt(serverNowNanos);
        List<SyncedTweenState> active = tweens.stream()
                .filter(tween -> !tween.isComplete(serverNowNanos))
                .toList();
        return toBuilder()
                .volume(volumeAt(serverNowNanos))
                .pitch(pitchAt(serverNowNanos))
                .speed(speedAt(serverNowNanos))
                .position(position)
                .minDistance(minDistanceAt(serverNowNanos))
                .maxDistance(maxDistanceAt(serverNowNanos))
                .playbackPositionSeconds(playbackPositionAt(serverNowNanos))
                .playbackAnchorServerNanos(serverNowNanos)
                .tweens(active)
                .build();
    }

    public SyncedTweenState tween(TweenControlPacket.Property property) {
        for (SyncedTweenState tween : tweens) {
            if (tween.property() == property) return tween;
        }
        return null;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(revision);
        buffer.writeUtf(id, MAX_ID_LENGTH);
        buffer.writeResourceLocation(soundEventId);
        buffer.writeBoolean(streamed);
        buffer.writeVarInt(playbackStatus.ordinal());
        buffer.writeFloat(volume);
        buffer.writeFloat(pitch);
        buffer.writeFloat(speed);
        buffer.writeBoolean(staticSound);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeBoolean(looping);
        buffer.writeVarInt(priority);
        buffer.writeFloat(minDistance);
        buffer.writeFloat(maxDistance);
        buffer.writeUtf(busName, MAX_BUS_NAME_LENGTH);
        buffer.writeVarInt(bindingKind.ordinal());
        buffer.writeVarInt(entityId);
        buffer.writeUUID(entityUuid);
        buffer.writeBlockPos(blockPos);
        buffer.writeDouble(durationSeconds);
        buffer.writeDouble(playbackPositionSeconds);
        buffer.writeLong(playbackAnchorServerNanos);
        buffer.writeVarInt(tweens.size());
        for (SyncedTweenState tween : tweens) tween.encode(buffer);
    }

    public static SyncedSoundState decode(FriendlyByteBuf buffer) {
        long revision = buffer.readVarLong();
        String id = buffer.readUtf(MAX_ID_LENGTH);
        ResourceLocation event = buffer.readResourceLocation();
        boolean streamed = buffer.readBoolean();
        PlaybackStatus status = enumValue(PlaybackStatus.values(), buffer.readVarInt(), "playback status");
        float volume = buffer.readFloat();
        float pitch = buffer.readFloat();
        float speed = buffer.readFloat();
        boolean staticSound = buffer.readBoolean();
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        boolean looping = buffer.readBoolean();
        int priority = buffer.readVarInt();
        float minDistance = buffer.readFloat();
        float maxDistance = buffer.readFloat();
        String bus = buffer.readUtf(MAX_BUS_NAME_LENGTH);
        BindingKind binding = enumValue(BindingKind.values(), buffer.readVarInt(), "binding kind");
        int entityId = buffer.readVarInt();
        UUID entityUuid = buffer.readUUID();
        BlockPos blockPos = buffer.readBlockPos();
        double duration = buffer.readDouble();
        double playbackPosition = buffer.readDouble();
        long anchor = buffer.readLong();
        int tweenCount = buffer.readVarInt();
        if (tweenCount < 0 || tweenCount > MAX_TWEENS) {
            throw new IllegalArgumentException("Invalid Auralis tween count: " + tweenCount);
        }
        List<SyncedTweenState> tweens = new ArrayList<>(tweenCount);
        for (int index = 0; index < tweenCount; index++) tweens.add(SyncedTweenState.decode(buffer));
        return new SyncedSoundState(
                revision, id, event, streamed, status,
                volume, pitch, speed, staticSound,
                x, y, z, looping, priority, minDistance, maxDistance, bus,
                binding, entityId, entityUuid, blockPos,
                duration, playbackPosition, anchor, tweens
        );
    }

    private double integratePlaybackRate(long fromNanos, long toNanos) {
        SyncedTweenState pitchTween = tween(TweenControlPacket.Property.PITCH);
        SyncedTweenState speedTween = tween(TweenControlPacket.Property.SPEED);
        if (pitchTween == null && speedTween == null) {
            return (toNanos - fromNanos) / 1_000_000_000.0 * playbackRateAt(fromNanos);
        }

        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(fromNanos);
        boundaries.add(toNanos);
        addBoundaries(boundaries, pitchTween, fromNanos, toNanos);
        addBoundaries(boundaries, speedTween, fromNanos, toNanos);
        List<Long> points = new ArrayList<>(boundaries);
        double mediaSeconds = 0.0;
        for (int index = 1; index < points.size(); index++) {
            long start = points.get(index - 1);
            long end = points.get(index);
            if (end <= start) continue;
            mediaSeconds += simpsonRateIntegral(start, end, 16);
        }
        return mediaSeconds;
    }

    private double simpsonRateIntegral(long start, long end, int steps) {
        int evenSteps = Math.max(2, steps + (steps & 1));
        double widthSeconds = (end - start) / 1_000_000_000.0 / evenSteps;
        double sum = playbackRateAt(start) + playbackRateAt(end);
        for (int index = 1; index < evenSteps; index++) {
            long sample = start + (long) ((end - start) * (index / (double) evenSteps));
            sum += playbackRateAt(sample) * (index % 2 == 0 ? 2.0 : 4.0);
        }
        return widthSeconds * sum / 3.0;
    }

    private double playbackRateAt(long serverNanos) {
        return Math.max(0.01, Math.min(8.0, pitchAt(serverNanos) * speedAt(serverNanos)));
    }

    private double normalizePosition(double position) {
        double normalized = Math.max(0.0, position);
        if (durationSeconds <= 0.0) return Math.min(MAX_MEDIA_SECONDS, normalized);
        if (looping) {
            normalized %= durationSeconds;
            if (normalized < 0.0) normalized += durationSeconds;
            return normalized;
        }
        return Math.min(durationSeconds, normalized);
    }

    private float scalarAt(
            TweenControlPacket.Property property,
            float fallback,
            long serverNowNanos,
            float minimum,
            float maximum
    ) {
        SyncedTweenState tween = tween(property);
        float value = tween == null ? fallback : (float) tween.xAt(serverNowNanos);
        return clampFinite(value, minimum, maximum, fallback);
    }

    private static void addBoundaries(
            TreeSet<Long> boundaries,
            SyncedTweenState tween,
            long from,
            long to
    ) {
        if (tween == null) return;
        if (tween.startServerNanos() > from && tween.startServerNanos() < to) {
            boundaries.add(tween.startServerNanos());
        }
        long end = tween.endServerNanos();
        if (end > from && end < to) boundaries.add(end);
    }

    private static String bounded(String value, int maxLength, String label) {
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid Auralis " + label + " length: " + value.length());
        }
        return value;
    }

    private static float clampFinite(float value, float minimum, float maximum, float fallback) {
        float finite = Float.isFinite(value) ? value : fallback;
        return Math.max(minimum, Math.min(maximum, finite));
    }

    private static double clampFinite(double value, double minimum, double maximum, double fallback) {
        double finite = Double.isFinite(value) ? value : fallback;
        return Math.max(minimum, Math.min(maximum, finite));
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

    public static final class Builder {
        private long revision;
        private String id;
        private ResourceLocation soundEventId;
        private boolean streamed;
        private PlaybackStatus playbackStatus = PlaybackStatus.PLAYING;
        private float volume = 1.0f;
        private float pitch = 1.0f;
        private float speed = 1.0f;
        private boolean staticSound;
        private double x;
        private double y;
        private double z;
        private boolean looping;
        private int priority = 50;
        private float minDistance = 1.0f;
        private float maxDistance = 48.0f;
        private String busName = AudioBusSystem.MASTER;
        private BindingKind bindingKind = BindingKind.NONE;
        private int entityId;
        private UUID entityUuid = ZERO_UUID;
        private BlockPos blockPos = BlockPos.ZERO;
        private double durationSeconds;
        private double playbackPositionSeconds;
        private long playbackAnchorServerNanos;
        private List<SyncedTweenState> tweens = List.of();

        public Builder(String id, ResourceLocation soundEventId, long serverNowNanos) {
            this.id = id;
            this.soundEventId = soundEventId;
            this.playbackAnchorServerNanos = serverNowNanos;
        }

        private Builder(SyncedSoundState state) {
            revision = state.revision;
            id = state.id;
            soundEventId = state.soundEventId;
            streamed = state.streamed;
            playbackStatus = state.playbackStatus;
            volume = state.volume;
            pitch = state.pitch;
            speed = state.speed;
            staticSound = state.staticSound;
            x = state.x;
            y = state.y;
            z = state.z;
            looping = state.looping;
            priority = state.priority;
            minDistance = state.minDistance;
            maxDistance = state.maxDistance;
            busName = state.busName;
            bindingKind = state.bindingKind;
            entityId = state.entityId;
            entityUuid = state.entityUuid;
            blockPos = state.blockPos;
            durationSeconds = state.durationSeconds;
            playbackPositionSeconds = state.playbackPositionSeconds;
            playbackAnchorServerNanos = state.playbackAnchorServerNanos;
            tweens = state.tweens;
        }

        public Builder revision(long value) { revision = value; return this; }
        public Builder streamed(boolean value) { streamed = value; return this; }
        public Builder playbackStatus(PlaybackStatus value) { playbackStatus = value; return this; }
        public Builder volume(float value) { volume = value; return this; }
        public Builder pitch(float value) { pitch = value; return this; }
        public Builder speed(float value) { speed = value; return this; }
        public Builder staticSound(boolean value) { staticSound = value; return this; }
        public Builder position(Vec3 value) {
            Objects.requireNonNull(value, "position");
            x = value.x; y = value.y; z = value.z;
            return this;
        }
        public Builder looping(boolean value) { looping = value; return this; }
        public Builder priority(int value) { priority = value; return this; }
        public Builder minDistance(float value) { minDistance = value; return this; }
        public Builder maxDistance(float value) { maxDistance = value; return this; }
        public Builder busName(String value) { busName = value; return this; }
        public Builder bindEntity(int id, UUID uuid) {
            bindingKind = BindingKind.ENTITY;
            entityId = id;
            entityUuid = Objects.requireNonNullElse(uuid, ZERO_UUID);
            blockPos = BlockPos.ZERO;
            return this;
        }
        public Builder bindBlock(BlockPos position) {
            bindingKind = BindingKind.BLOCK;
            blockPos = Objects.requireNonNull(position, "blockPos");
            entityId = 0;
            entityUuid = ZERO_UUID;
            return this;
        }
        public Builder unbind() {
            bindingKind = BindingKind.NONE;
            entityId = 0;
            entityUuid = ZERO_UUID;
            blockPos = BlockPos.ZERO;
            return this;
        }
        public Builder durationSeconds(double value) { durationSeconds = value; return this; }
        public Builder playbackPositionSeconds(double value) { playbackPositionSeconds = value; return this; }
        public Builder playbackAnchorServerNanos(long value) { playbackAnchorServerNanos = value; return this; }
        public Builder tweens(List<SyncedTweenState> value) { tweens = List.copyOf(value); return this; }
        public Builder replaceTween(SyncedTweenState value) {
            List<SyncedTweenState> updated = new ArrayList<>(tweens);
            updated.removeIf(existing -> existing.property() == value.property());
            updated.add(value);
            tweens = List.copyOf(updated);
            return this;
        }
        public Builder removeTween(TweenControlPacket.Property property) {
            tweens = tweens.stream().filter(tween -> tween.property() != property).toList();
            return this;
        }

        public SyncedSoundState build() {
            return new SyncedSoundState(
                    revision, id, soundEventId, streamed, playbackStatus,
                    volume, pitch, speed, staticSound,
                    x, y, z, looping, priority, minDistance, maxDistance, busName,
                    bindingKind, entityId, entityUuid, blockPos,
                    durationSeconds, playbackPositionSeconds, playbackAnchorServerNanos, tweens
            );
        }
    }
}
