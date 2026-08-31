package org.lytharalab.gfbs.auralis.api.source;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Thread-safe bounded LIVE source for third-party PCM generators and network
 * receivers. Producers call {@link #offer(ByteBuffer)} off the audio thread;
 * Auralis drains complete PCM frames without blocking.
 */
public final class PushPcmAudioDataSource implements AudioDataSource {
    private final PcmFormat format;
    private final int capacityBytes;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int queuedBytes;
    private int headOffset;
    private boolean finished;
    private boolean closed;

    public PushPcmAudioDataSource(PcmFormat format, int capacityBytes) {
        this.format = Objects.requireNonNull(format, "format");
        if (capacityBytes < format.frameSizeBytes()) {
            throw new IllegalArgumentException("capacityBytes must hold at least one PCM frame");
        }
        this.capacityBytes = capacityBytes - (capacityBytes % format.frameSizeBytes());
    }

    @Override public PcmFormat format() { return format; }
    @Override public AudioSourceMode mode() { return AudioSourceMode.LIVE; }

    /** Copies and queues the source buffer's remaining whole frames. */
    public synchronized boolean offer(ByteBuffer pcm) {
        Objects.requireNonNull(pcm, "pcm");
        if (closed || finished) return false;
        int bytes = pcm.remaining();
        if (bytes == 0 || (bytes % format.frameSizeBytes()) != 0) {
            throw new IllegalArgumentException("PCM chunk must contain one or more whole frames");
        }
        if (bytes > capacityBytes - queuedBytes) return false;
        byte[] copy = new byte[bytes];
        pcm.get(copy);
        chunks.addLast(copy);
        queuedBytes += bytes;
        return true;
    }

    /** Signals that END should be returned after all queued PCM is consumed. */
    public synchronized void finish() { finished = true; }

    public synchronized int queuedBytes() { return queuedBytes; }
    public int capacityBytes() { return capacityBytes; }

    @Override
    public synchronized AudioReadResult read(ByteBuffer target) {
        if (closed) return AudioReadResult.END;
        int frameBytes = format.frameSizeBytes();
        int writable = target.remaining() - (target.remaining() % frameBytes);
        int written = 0;
        while (writable > 0 && !chunks.isEmpty()) {
            byte[] head = chunks.peekFirst();
            int count = Math.min(writable, head.length - headOffset);
            target.put(head, headOffset, count);
            headOffset += count;
            writable -= count;
            written += count;
            queuedBytes -= count;
            if (headOffset == head.length) {
                chunks.removeFirst();
                headOffset = 0;
            }
        }
        if (written > 0) return AudioReadResult.DATA;
        return finished ? AudioReadResult.END : AudioReadResult.WAIT;
    }

    @Override
    public synchronized void close() {
        closed = true;
        finished = true;
        chunks.clear();
        queuedBytes = 0;
        headOffset = 0;
    }
}
