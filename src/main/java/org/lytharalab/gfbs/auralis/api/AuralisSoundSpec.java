package org.lytharalab.gfbs.auralis.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;

import java.util.Objects;

/** Complete immutable description of a server-authoritative sound. */
public record AuralisSoundSpec(
        ResourceLocation soundEventId, boolean streamed, float volume, float pitch,
        float speed, boolean listenerRelative, Vec3 position, boolean looping,
        int priority, float minDistance, float maxDistance, String bus
) {
    public AuralisSoundSpec {
        Objects.requireNonNull(soundEventId, "soundEventId");
        Objects.requireNonNull(position, "position");
        bus = Objects.requireNonNull(bus, "bus").trim();
        if (bus.isEmpty() || bus.length() > 128) throw new IllegalArgumentException("Invalid bus name");
        volume = finiteClamp(volume, 0.0f, 16.0f, "volume");
        pitch = finiteClamp(pitch, 0.01f, 8.0f, "pitch");
        speed = finiteClamp(speed, 0.01f, 8.0f, "speed");
        priority = Math.max(0, Math.min(100, priority));
        minDistance = finiteClamp(minDistance, 0.0f, 1_000_000.0f, "minDistance");
        maxDistance = finiteClamp(maxDistance, minDistance, 1_000_000.0f, "maxDistance");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) throw new IllegalArgumentException("position");
    }

    public static Builder builder(ResourceLocation eventId) { return new Builder(eventId); }
    public static Builder builder(SoundEvent event) { return builder(event.getLocation()); }

    private static float finiteClamp(float value, float min, float max, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name);
        return Math.max(min, Math.min(max, value));
    }

    public static final class Builder {
        private final ResourceLocation eventId;
        private boolean streamed;
        private float volume = 1.0f, pitch = 1.0f, speed = 1.0f;
        private boolean listenerRelative, looping;
        private Vec3 position = Vec3.ZERO;
        private int priority = 50;
        private float minDistance = 1.0f, maxDistance = 48.0f;
        private String bus = AudioBusSystem.MASTER;

        private Builder(ResourceLocation eventId) { this.eventId = Objects.requireNonNull(eventId, "eventId"); }
        public Builder streamed(boolean value) { streamed = value; return this; }
        public Builder volume(float value) { volume = value; return this; }
        public Builder pitch(float value) { pitch = value; return this; }
        public Builder speed(float value) { speed = value; return this; }
        public Builder listenerRelative(boolean value) { listenerRelative = value; return this; }
        public Builder position(Vec3 value) { position = value; return this; }
        public Builder looping(boolean value) { looping = value; return this; }
        public Builder priority(int value) { priority = value; return this; }
        public Builder distances(float min, float max) { minDistance = min; maxDistance = max; return this; }
        public Builder bus(String value) { bus = value; return this; }
        public AuralisSoundSpec build() { return new AuralisSoundSpec(eventId, streamed, volume, pitch, speed, listenerRelative, position, looping, priority, minDistance, maxDistance, bus); }
    }
}
