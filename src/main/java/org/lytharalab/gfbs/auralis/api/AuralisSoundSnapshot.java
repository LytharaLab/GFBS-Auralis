package org.lytharalab.gfbs.auralis.api;

import net.minecraft.world.phys.Vec3;

/** Immutable local voice state captured after an operation commits. */
public record AuralisSoundSnapshot(
        long revision, AuralisPlaybackState state, double playbackPositionSeconds,
        double durationSeconds, boolean materialized, float volume, float pitch,
        float speed, boolean listenerRelative, Vec3 position, boolean looping,
        int priority, float minDistance, float maxDistance, String bus
) { }
