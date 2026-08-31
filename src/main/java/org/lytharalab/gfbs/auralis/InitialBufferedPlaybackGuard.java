package org.lytharalab.gfbs.auralis;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Issues a one-shot token for the first scheduler pass of a new buffered
 * playback. The generation check prevents a delayed scheduler pass from
 * rebasing a later stop/replay cycle.
 */
final class InitialBufferedPlaybackGuard {
    static final long NONE = 0L;

    private final AtomicLong generation = new AtomicLong(NONE);
    private final AtomicLong pending = new AtomicLong(NONE);

    long beginNewPlayback(boolean protectInitialBufferedStart) {
        long next = nextGeneration();
        pending.set(protectInitialBufferedStart ? next : NONE);
        return next;
    }

    void invalidate() {
        nextGeneration();
        pending.set(NONE);
    }

    long claimForScheduling() {
        return pending.getAndSet(NONE);
    }

    boolean isCurrent(long token) {
        return token != NONE && generation.get() == token;
    }

    private long nextGeneration() {
        return generation.updateAndGet(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }
}
