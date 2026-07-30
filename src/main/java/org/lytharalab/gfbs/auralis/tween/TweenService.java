package org.lytharalab.gfbs.auralis.tween;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class TweenService {
    private final List<Tween> tweens = new ArrayList<>();

    public Tween create(DoubleSupplier getter, DoubleConsumer setter, TweenInfo info, double targetValue) {
        Tween t = new Tween(info, getter, setter, targetValue);
        tweens.add(t);
        return t;
    }

    public void tick(double dtSeconds) {
        if (tweens.isEmpty()) return;

        Iterator<Tween> it = tweens.iterator();
        while (it.hasNext()) {
            Tween t = it.next();
            boolean alive = t.tick(dtSeconds);
            if (!alive) it.remove();
        }
    }

    public void cancelAll() {
        for (Tween t : tweens) t.cancel();
        tweens.clear();
    }

    public int size() {
        return tweens.size();
    }
}
