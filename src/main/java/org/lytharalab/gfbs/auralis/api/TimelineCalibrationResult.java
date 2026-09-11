package org.lytharalab.gfbs.auralis.api;

public record TimelineCalibrationResult(Action action, double errorSeconds, double rateMultiplier) {
    public enum Action { SETTLED, SMOOTH, HARD_SEEK }
}
