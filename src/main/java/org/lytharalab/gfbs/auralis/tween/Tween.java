package org.lytharalab.gfbs.auralis.tween;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class Tween {
    private final TweenInfo info;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;

    private final double startValue;
    private final double targetValue;

    private PlaybackState state = PlaybackState.READY;

    private double timer = 0.0;
    private double delayTimer = 0.0;
    private int loopsDone = 0;
    private boolean reversedPhase = false;
    private boolean inRepeatDelay = false;

    private Consumer<Tween> completed;
    private Consumer<Tween> canceled;

    Tween(TweenInfo info, DoubleSupplier getter, DoubleConsumer setter, double targetValue) {
        this.info = Objects.requireNonNull(info, "TweenInfo");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.startValue = getter.getAsDouble();
        this.targetValue = targetValue;
        this.delayTimer = info.delayTime;
    }

    public Tween onCompleted(Consumer<Tween> cb) {
        this.completed = cb;
        return this;
    }

    public Tween onCanceled(Consumer<Tween> cb) {
        this.canceled = cb;
        return this;
    }

    public PlaybackState getState() {
        return state;
    }

    public void play() {
        if (state == PlaybackState.COMPLETED || state == PlaybackState.CANCELED) return;
        if (state == PlaybackState.READY) {
            // reset to initial state
            this.timer = 0.0;
            this.delayTimer = info.delayTime;
            this.loopsDone = 0;
            this.reversedPhase = false;
            this.inRepeatDelay = false;
        }
        state = PlaybackState.PLAYING;
    }

    public void pause() {
        if (state == PlaybackState.PLAYING) state = PlaybackState.PAUSED;
    }

    public void cancel() {
        if (state == PlaybackState.CANCELED || state == PlaybackState.COMPLETED) return;
        state = PlaybackState.CANCELED;
        if (canceled != null) canceled.accept(this);
    }

    public void completeNow() {
        if (state == PlaybackState.CANCELED || state == PlaybackState.COMPLETED) return;
        setter.accept(targetValue);
        state = PlaybackState.COMPLETED;
        if (completed != null) completed.accept(this);
    }

    boolean tick(double dtSeconds) {
        if (state != PlaybackState.PLAYING) return !(state == PlaybackState.COMPLETED || state == PlaybackState.CANCELED);
        if (dtSeconds <= 0) return true;

        // start delay
        if (delayTimer > 0.0) {
            delayTimer -= dtSeconds;
            return true;
        }

        // repeat delay (between loops)
        if (inRepeatDelay) {
            delayTimer -= dtSeconds;
            if (delayTimer <= 0.0) {
                inRepeatDelay = false;
                timer = 0.0;
                if (info.reverses) reversedPhase = !reversedPhase;
            }
            return true;
        }

        double duration = Math.max(1e-9, info.timeSeconds);
        timer += dtSeconds;

        double alpha = timer / duration;
        if (alpha > 1.0) alpha = 1.0;

        double eased = Easing.ease(info.easingStyle, info.easingDirection, alpha);

        double from = reversedPhase ? targetValue : startValue;
        double to = reversedPhase ? startValue : targetValue;

        double value = from + (to - from) * eased;
        setter.accept(value);

        if (timer >= duration) {
            if (shouldLoopAgain()) {
                loopsDone++;
                if (info.repeatDelay > 0.0) {
                    inRepeatDelay = true;
                    delayTimer = info.repeatDelay;
                } else {
                    timer = 0.0;
                    if (info.reverses) reversedPhase = !reversedPhase;
                }
                return true;
            }

            state = PlaybackState.COMPLETED;
            if (completed != null) completed.accept(this);
            return false;
        }

        return true;
    }

    private boolean shouldLoopAgain() {
        if (info.repeatCount < 0) return true;         // infinite
        return loopsDone < info.repeatCount;           // 0 means no extra loop
    }
}
