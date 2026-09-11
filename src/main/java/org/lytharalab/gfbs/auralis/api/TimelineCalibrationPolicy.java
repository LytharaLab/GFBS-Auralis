package org.lytharalab.gfbs.auralis.api;

/** Thresholds for continuously reconciling a physical cursor with server time. */
public record TimelineCalibrationPolicy(
        double settledToleranceSeconds, double hardSeekThresholdSeconds,
        double convergenceSeconds, double maximumRateAdjustment
) {
    public TimelineCalibrationPolicy {
        if (!(settledToleranceSeconds >= 0.0) || !Double.isFinite(settledToleranceSeconds)) throw new IllegalArgumentException("settledToleranceSeconds");
        if (!(hardSeekThresholdSeconds > settledToleranceSeconds) || !Double.isFinite(hardSeekThresholdSeconds)) throw new IllegalArgumentException("hardSeekThresholdSeconds");
        if (!(convergenceSeconds > 0.0) || !Double.isFinite(convergenceSeconds)) throw new IllegalArgumentException("convergenceSeconds");
        if (!(maximumRateAdjustment >= 0.0 && maximumRateAdjustment <= 0.5) || !Double.isFinite(maximumRateAdjustment)) throw new IllegalArgumentException("maximumRateAdjustment");
    }

    public static TimelineCalibrationPolicy defaults() {
        return new TimelineCalibrationPolicy(0.025, 0.750, 2.0, 0.04);
    }
}
