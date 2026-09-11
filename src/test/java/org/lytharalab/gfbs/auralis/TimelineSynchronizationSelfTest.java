package org.lytharalab.gfbs.auralis;

import org.lytharalab.gfbs.auralis.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic tests for the parts of synchronization that must not depend on a running game. */
public final class TimelineSynchronizationSelfTest {
    private TimelineSynchronizationSelfTest() { }

    public static void main(String[] args) {
        authoritativeProjectionUsesServiceTicks();
        pausedTimelineDoesNotAdvance();
        loopingProjectionWraps();
        calibrationThresholdsAndBounds();
        loopingCalibrationUsesShortestPath();
        stalePhysicalCursorExposesAudioThreadStall();
        estimatorReanchorsAfterClockDiscontinuity();
        operationCallbackWaitsForActualCompletion();
    }

    private static void authoritativeProjectionUsesServiceTicks() {
        near(3.0, AuthoritativeTimelineMath.positionAt(AuralisPlaybackState.PLAYING,
                1.0, 100, 120.0, 2.0f, false, 0.0), 1e-9, "service tick projection");
    }

    private static void pausedTimelineDoesNotAdvance() {
        near(7.5, AuthoritativeTimelineMath.positionAt(AuralisPlaybackState.PAUSED,
                7.5, 100, 1000.0, 8.0f, false, 0.0), 1e-9, "paused anchor");
    }

    private static void loopingProjectionWraps() {
        near(1.0, AuthoritativeTimelineMath.positionAt(AuralisPlaybackState.PLAYING,
                9.0, 0, 40.0, 1.0f, true, 10.0), 1e-9, "loop wrap");
    }

    private static void calibrationThresholdsAndBounds() {
        TimelineCalibrationPolicy policy = TimelineCalibrationPolicy.defaults();
        check(TimelineCalibrationMath.decide(10.010, 10.0, policy).action() == TimelineCalibrationResult.Action.SETTLED, "settled");
        TimelineCalibrationResult smooth = TimelineCalibrationMath.decide(10.20, 10.0, policy);
        check(smooth.action() == TimelineCalibrationResult.Action.SMOOTH, "smooth");
        near(1.04, smooth.rateMultiplier(), 1e-9, "smooth correction clamp");
        check(TimelineCalibrationMath.decide(11.0, 10.0, policy).action() == TimelineCalibrationResult.Action.HARD_SEEK, "hard seek");
    }

    private static void loopingCalibrationUsesShortestPath() {
        TimelineCalibrationResult result = TimelineCalibrationMath.decideLooping(0.1, 9.9, 10.0,
                TimelineCalibrationPolicy.defaults());
        near(0.2, result.errorSeconds(), 1e-9, "loop shortest error");
    }

    private static void stalePhysicalCursorExposesAudioThreadStall() {
        near(4.1, PhysicalCursorMath.project(4.0, 100_000_000L, true, true, 1.0, 250_000_000L),
                1e-9, "fresh cursor extrapolation");
        near(4.0, PhysicalCursorMath.project(4.0, 800_000_000L, true, true, 1.0, 250_000_000L),
                1e-9, "stale cursor must remain frozen");
    }

    private static void estimatorReanchorsAfterClockDiscontinuity() {
        ServerTickEstimator estimator = new ServerTickEstimator();
        estimator.accept(1_000_000_000L, 1_100_000_000L, 100L);
        near(101.0, estimator.estimate(1_100_000_000L, 0.0), 1e-9, "initial estimate");
        estimator.accept(20_000_000_000L, 20_100_000_000L, 102L);
        near(103.0, estimator.estimate(20_100_000_000L, 0.0), 1e-9, "hard reanchor");
    }

    private static void operationCallbackWaitsForActualCompletion() {
        CompletableFuture<String> committed = new CompletableFuture<>();
        AtomicBoolean callback = new AtomicBoolean(false);
        AuralisOperation.from(AuralisOperation.Kind.PLAY, committed).onComplete((value, failure) -> callback.set(true));
        check(!callback.get(), "operation completed before effect");
        committed.complete("done");
        check(callback.get(), "operation callback missing");
    }

    private static void near(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
