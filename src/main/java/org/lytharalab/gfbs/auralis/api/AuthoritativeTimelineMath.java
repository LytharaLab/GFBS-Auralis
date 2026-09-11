package org.lytharalab.gfbs.auralis.api;

public final class AuthoritativeTimelineMath {
    private AuthoritativeTimelineMath() { }

    public static double positionAt(AuralisPlaybackState state, double anchorSeconds, long anchorServerTick,
                                    double estimatedServerTick, float speed, boolean looping, double durationSeconds) {
        double position = Math.max(0.0, anchorSeconds);
        if (state == AuralisPlaybackState.PLAYING) {
            position += Math.max(0.0, estimatedServerTick - anchorServerTick) * 0.05 * Math.max(0.01f, speed);
        }
        if (durationSeconds > 0.0 && Double.isFinite(durationSeconds)) {
            if (looping) position %= durationSeconds;
            else position = Math.min(position, durationSeconds);
        }
        return Math.max(0.0, position);
    }
}
