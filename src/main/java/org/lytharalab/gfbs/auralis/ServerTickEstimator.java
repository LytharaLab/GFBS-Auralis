package org.lytharalab.gfbs.auralis;

/** Maps local monotonic time to the server service-tick domain using RTT midpoint samples. */
public final class ServerTickEstimator {
    private boolean initialized;
    private double anchorServerTick;
    private long anchorClientNanos;

    public synchronized void accept(long clientSendNanos, long clientReceiveNanos, long serverTick) {
        if (clientReceiveNanos < clientSendNanos) return;
        long midpoint = clientSendNanos + ((clientReceiveNanos - clientSendNanos) / 2L);
        if (!initialized) {
            initialized = true;
            anchorServerTick = serverTick;
            anchorClientNanos = midpoint;
            return;
        }
        double predicted = estimateLocked(midpoint);
        double error = serverTick - predicted;
        if (Math.abs(error) > 2.0) {
            anchorServerTick = serverTick;
            anchorClientNanos = midpoint;
        } else {
            anchorServerTick = predicted + error * 0.20;
            anchorClientNanos = midpoint;
        }
    }

    public synchronized double estimate(long clientNanos, double fallbackServerTick) {
        return initialized ? estimateLocked(clientNanos) : fallbackServerTick;
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public synchronized void reset() {
        initialized = false;
        anchorServerTick = 0.0;
        anchorClientNanos = 0L;
    }

    private double estimateLocked(long clientNanos) {
        return anchorServerTick + Math.max(0L, clientNanos - anchorClientNanos) / 50_000_000.0;
    }
}
