package org.lytharalab.gfbs.auralis.tween;

public final class Easing {
    private Easing() {}

    public static double ease(EasingStyle style, EasingDirection dir, double t) {
        t = clamp01(t);

        switch (style) {
            case LINEAR:
                return t;

            case QUAD:
                return applyDir(dir, t, Easing::inQuad, Easing::outQuad, Easing::inOutQuad);

            case CUBIC:
                return applyDir(dir, t, Easing::inCubic, Easing::outCubic, Easing::inOutCubic);

            case QUART:
                return applyDir(dir, t, Easing::inQuart, Easing::outQuart, Easing::inOutQuart);

            case QUINT:
                return applyDir(dir, t, Easing::inQuint, Easing::outQuint, Easing::inOutQuint);

            case SINE:
                return applyDir(dir, t, Easing::inSine, Easing::outSine, Easing::inOutSine);

            case EXPO:
                return applyDir(dir, t, Easing::inExpo, Easing::outExpo, Easing::inOutExpo);

            case CIRC:
                return applyDir(dir, t, Easing::inCirc, Easing::outCirc, Easing::inOutCirc);

            case BACK:
                return applyDir(dir, t, Easing::inBack, Easing::outBack, Easing::inOutBack);

            case ELASTIC:
                return applyDir(dir, t, Easing::inElastic, Easing::outElastic, Easing::inOutElastic);

            case BOUNCE:
                return applyDir(dir, t, Easing::inBounce, Easing::outBounce, Easing::inOutBounce);

            default:
                return t;
        }
    }

    private interface Fn { double f(double t); }

    private static double applyDir(EasingDirection dir, double t, Fn in, Fn out, Fn inOut) {
        switch (dir) {
            case IN: return in.f(t);
            case OUT: return out.f(t);
            case IN_OUT: return inOut.f(t);
            default: return t;
        }
    }

    private static double clamp01(double t) {
        if (t < 0) return 0;
        if (t > 1) return 1;
        return t;
    }

    // ---- Quad
    private static double inQuad(double t) { return t * t; }
    private static double outQuad(double t) { return 1 - (1 - t) * (1 - t); }
    private static double inOutQuad(double t) {
        return (t < 0.5) ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    // ---- Cubic
    private static double inCubic(double t) { return t * t * t; }
    private static double outCubic(double t) { return 1 - Math.pow(1 - t, 3); }
    private static double inOutCubic(double t) {
        return (t < 0.5) ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    // ---- Quart
    private static double inQuart(double t) { return t * t * t * t; }
    private static double outQuart(double t) { return 1 - Math.pow(1 - t, 4); }
    private static double inOutQuart(double t) {
        return (t < 0.5) ? 8 * Math.pow(t, 4) : 1 - Math.pow(-2 * t + 2, 4) / 2;
    }

    // ---- Quint
    private static double inQuint(double t) { return Math.pow(t, 5); }
    private static double outQuint(double t) { return 1 - Math.pow(1 - t, 5); }
    private static double inOutQuint(double t) {
        return (t < 0.5) ? 16 * Math.pow(t, 5) : 1 - Math.pow(-2 * t + 2, 5) / 2;
    }

    // ---- Sine
    private static double inSine(double t) { return 1 - Math.cos((t * Math.PI) / 2); }
    private static double outSine(double t) { return Math.sin((t * Math.PI) / 2); }
    private static double inOutSine(double t) { return -(Math.cos(Math.PI * t) - 1) / 2; }

    // ---- Expo
    private static double inExpo(double t) { return t == 0 ? 0 : Math.pow(2, 10 * t - 10); }
    private static double outExpo(double t) { return t == 1 ? 1 : 1 - Math.pow(2, -10 * t); }
    private static double inOutExpo(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        if (t < 0.5) return Math.pow(2, 20 * t - 10) / 2;
        return (2 - Math.pow(2, -20 * t + 10)) / 2;
    }

    // ---- Circ
    private static double inCirc(double t) { return 1 - Math.sqrt(1 - t * t); }
    private static double outCirc(double t) { return Math.sqrt(1 - Math.pow(t - 1, 2)); }
    private static double inOutCirc(double t) {
        return (t < 0.5)
                ? (1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2
                : (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
    }

    // ---- Back
    private static double inBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return c3 * t * t * t - c1 * t * t;
    }
    private static double outBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }
    private static double inOutBack(double t) {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return (t < 0.5)
                ? (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
                : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
    }

    // ---- Elastic
    private static double inElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        double c4 = (2 * Math.PI) / 3;
        return -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * c4);
    }
    private static double outElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        double c4 = (2 * Math.PI) / 3;
        return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1;
    }
    private static double inOutElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        double c5 = (2 * Math.PI) / 4.5;
        if (t < 0.5) {
            return -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * c5)) / 2;
        }
        return (Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * c5)) / 2 + 1;
    }

    // ---- Bounce
    private static double outBounce(double t) {
        double n1 = 7.5625;
        double d1 = 2.75;

        if (t < 1 / d1) {
            return n1 * t * t;
        } else if (t < 2 / d1) {
            t -= 1.5 / d1;
            return n1 * t * t + 0.75;
        } else if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            return n1 * t * t + 0.9375;
        } else {
            t -= 2.625 / d1;
            return n1 * t * t + 0.984375;
        }
    }
    private static double inBounce(double t) {
        return 1 - outBounce(1 - t);
    }
    private static double inOutBounce(double t) {
        return (t < 0.5)
                ? (1 - outBounce(1 - 2 * t)) / 2
                : (1 + outBounce(2 * t - 1)) / 2;
    }
}
