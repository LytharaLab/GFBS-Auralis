package org.lytharalab.gfbs.auralis.api;

import java.util.Objects;

public record AuthoritativeSoundSnapshot(
        String id, long epoch, long revision, AuralisSoundSpec spec,
        AuralisPlaybackState state, long anchorServerTick,
        double anchorPositionSeconds, double durationSeconds, boolean global
) {
    public AuthoritativeSoundSnapshot {
        id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(state, "state");
    }

    public double expectedPosition(double estimatedServerTick) {
        return AuthoritativeTimelineMath.positionAt(state, anchorPositionSeconds, anchorServerTick,
                estimatedServerTick, spec.pitch() * spec.speed(), spec.looping(), durationSeconds);
    }
}
