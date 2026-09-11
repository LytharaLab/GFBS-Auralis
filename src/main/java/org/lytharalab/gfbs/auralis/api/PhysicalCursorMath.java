package org.lytharalab.gfbs.auralis.api;

/** Pure rule for short extrapolation of a sampled device cursor. */
public final class PhysicalCursorMath {
    private PhysicalCursorMath() { }

    public static double project(double sampleSeconds, long ageNanos, boolean rendererPlaying,
                                 boolean logicalPlaying, double mediaRate, long maximumAgeNanos) {
        if (!Double.isFinite(sampleSeconds)) return sampleSeconds;
        if (!rendererPlaying || !logicalPlaying || ageNanos < 0L || ageNanos > maximumAgeNanos) return sampleSeconds;
        double rate = Double.isFinite(mediaRate) ? Math.max(0.01, Math.min(8.0, mediaRate)) : 1.0;
        return sampleSeconds + ageNanos / 1_000_000_000.0 * rate;
    }
}
