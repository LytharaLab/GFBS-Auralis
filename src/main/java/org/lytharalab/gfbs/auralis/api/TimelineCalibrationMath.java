package org.lytharalab.gfbs.auralis.api;

/** Pure calibration math, deliberately independent of Minecraft and OpenAL. */
public final class TimelineCalibrationMath {
    private TimelineCalibrationMath() { }

    public static TimelineCalibrationResult decide(double expected, double actual, TimelineCalibrationPolicy policy) {
        return decideError(expected - actual, policy);
    }

    public static TimelineCalibrationResult decideLooping(double expected, double actual, double duration, TimelineCalibrationPolicy policy) {
        if (!(duration > 0.0) || !Double.isFinite(duration)) return decide(expected, actual, policy);
        double error = (expected - actual) % duration;
        if (error > duration * 0.5) error -= duration;
        if (error < -duration * 0.5) error += duration;
        return decideError(error, policy);
    }

    private static TimelineCalibrationResult decideError(double error, TimelineCalibrationPolicy policy) {
        if (!Double.isFinite(error)) return new TimelineCalibrationResult(TimelineCalibrationResult.Action.HARD_SEEK, error, 1.0);
        double absolute = Math.abs(error);
        if (absolute <= policy.settledToleranceSeconds()) return new TimelineCalibrationResult(TimelineCalibrationResult.Action.SETTLED, error, 1.0);
        if (absolute >= policy.hardSeekThresholdSeconds()) return new TimelineCalibrationResult(TimelineCalibrationResult.Action.HARD_SEEK, error, 1.0);
        double correction = Math.max(-policy.maximumRateAdjustment(), Math.min(policy.maximumRateAdjustment(), error / policy.convergenceSeconds()));
        return new TimelineCalibrationResult(TimelineCalibrationResult.Action.SMOOTH, error, 1.0 + correction);
    }
}
