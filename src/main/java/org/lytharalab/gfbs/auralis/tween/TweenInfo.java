package org.lytharalab.gfbs.auralis.tween;

public final class TweenInfo {
    public final double timeSeconds;
    public final EasingStyle easingStyle;
    public final EasingDirection easingDirection;
    public final int repeatCount;      // -1 = infinite
    public final boolean reverses;     // yoyo
    public final double delayTime;     // start delay
    public final double repeatDelay;   // delay between loops

    private TweenInfo(Builder b) {
        this.timeSeconds = Math.max(0.0, b.timeSeconds);
        this.easingStyle = b.easingStyle;
        this.easingDirection = b.easingDirection;
        this.repeatCount = b.repeatCount;
        this.reverses = b.reverses;
        this.delayTime = Math.max(0.0, b.delayTime);
        this.repeatDelay = Math.max(0.0, b.repeatDelay);
    }

    public static Builder of(double timeSeconds) {
        return new Builder(timeSeconds);
    }

    public static final class Builder {
        private double timeSeconds;
        private EasingStyle easingStyle = EasingStyle.QUAD;
        private EasingDirection easingDirection = EasingDirection.OUT;
        private int repeatCount = 0;
        private boolean reverses = false;
        private double delayTime = 0.0;
        private double repeatDelay = 0.0;

        private Builder(double timeSeconds) {
            this.timeSeconds = timeSeconds;
        }

        public Builder easing(EasingStyle style, EasingDirection dir) {
            this.easingStyle = (style == null) ? EasingStyle.LINEAR : style;
            this.easingDirection = (dir == null) ? EasingDirection.IN_OUT : dir;
            return this;
        }

        public Builder repeat(int repeatCount) {
            this.repeatCount = repeatCount;
            return this;
        }

        public Builder infinite() {
            this.repeatCount = -1;
            return this;
        }

        public Builder reverses(boolean reverses) {
            this.reverses = reverses;
            return this;
        }

        public Builder delay(double delaySeconds) {
            this.delayTime = delaySeconds;
            return this;
        }

        public Builder repeatDelay(double delaySeconds) {
            this.repeatDelay = delaySeconds;
            return this;
        }

        public TweenInfo build() {
            return new TweenInfo(this);
        }
    }
}
