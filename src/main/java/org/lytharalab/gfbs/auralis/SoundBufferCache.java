package org.lytharalab.gfbs.auralis;

/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 * <p>
 * This program is licensed under the MIT License.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is provided to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.openal.AL10;
import org.lytharalab.gfbs.auralis.utils.OggVorbisDecoder;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;

import java.io.InputStream;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class SoundBufferCache {
    private record Entry(int bufferId, AtomicInteger refs) {}

    /**
     * PCM builds own processor objects until the worker actually exits. A
     * cosmetic future cancellation must therefore never masquerade as task
     * completion and let a voice close a processor that is still executing.
     */
    private static final class NonCancellingFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private final Minecraft mc;
    private final AuralisAL al;
    // Streamed OGG data is kept in native memory for random access. Bound it.
    private final Map<ResourceLocation, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Integer, ResourceLocation> bufferToPath = new ConcurrentHashMap<>();
    private final Map<Integer, Double> bufferDurations = new ConcurrentHashMap<>();
    private final int maxStreamedBytes;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Set<CompletableFuture<?>> pendingLoads = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor asyncExecutor;

    SoundBufferCache(Minecraft mc, AuralisAL al, int streamedChunkSize, int maxStreamedBytes) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.al = Objects.requireNonNull(al, "al");
        this.maxStreamedBytes = Math.max(256 * 1024, maxStreamedBytes);

        int processors = Runtime.getRuntime().availableProcessors();
        int loaderThreads = Math.max(2, Math.min(4, Math.max(1, processors / 4)));
        AtomicInteger threadIndex = new AtomicInteger(0);
        this.asyncExecutor = new ThreadPoolExecutor(
                loaderThreads,
                loaderThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                task -> {
                    Thread thread = new Thread(task, "Auralis-AsyncLoader-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((t, failure) ->
                            GFBsAuralis.LOGGER.error("Uncaught failure on {}", t.getName(), failure));
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    int acquireBuffer(ResourceLocation soundPath) {
        Objects.requireNonNull(soundPath, "soundPath");

        while (true) {
            Entry existing = cache.get(soundPath);
            if (existing != null && tryIncrement(existing)) {
                return existing.bufferId();
            }

            try {
                int bufferId = loadBufferSync(soundPath);
                Entry newEntry = new Entry(bufferId, new AtomicInteger(1));

                while (true) {
                    Entry old = cache.putIfAbsent(soundPath, newEntry);
                    if (old == null) {
                        bufferToPath.put(bufferId, soundPath);
                        return bufferId;
                    }

                    if (tryIncrement(old)) {
                        bufferDurations.remove(bufferId);
                        deleteBuffersLater(new int[] { bufferId });
                        return old.bufferId();
                    }

                    if (cache.replace(soundPath, old, newEntry)) {
                        bufferToPath.put(bufferId, soundPath);
                        return bufferId;
                    }
                }
            } catch (Exception e) {
                GFBsAuralis.LOGGER.error("Failed to acquire sound buffer for: {}", soundPath, e);
                return -1;
            }
        }
    }

    CompletableFuture<Integer> acquireBufferAsync(ResourceLocation soundPath) {
        Objects.requireNonNull(soundPath, "soundPath");
        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Auralis audio loader is shutting down"));
        }

        Entry existing = cache.get(soundPath);
        if (existing != null && tryIncrement(existing)) {
            return CompletableFuture.completedFuture(existing.bufferId());
        }

        return submitAsync(() -> {
            Entry e = cache.get(soundPath);
            if (e != null && tryIncrement(e)) {
                return e.bufferId();
            }

            int bufferId = loadBufferSync(soundPath);
            Entry newEntry = new Entry(bufferId, new AtomicInteger(1));

            while (true) {
                Entry old = cache.putIfAbsent(soundPath, newEntry);
                if (old == null) {
                    bufferToPath.put(bufferId, soundPath);
                    return bufferId;
                }

                if (tryIncrement(old)) {
                    bufferDurations.remove(bufferId);
                    deleteBuffersLater(new int[] { bufferId });
                    return old.bufferId();
                }

                if (cache.replace(soundPath, old, newEntry)) {
                    bufferToPath.put(bufferId, soundPath);
                    return bufferId;
                }
            }
        }, bufferId -> {
            if (bufferId != null && bufferId != -1) releaseBuffer(bufferId);
        });
    }

    private int loadBufferSync(ResourceLocation soundPath) {
        DecodedPcm pcm = decode(soundPath);
        if (pcm == null) {
            throw new RuntimeException("Failed to decode sound: " + soundPath);
        }
        assert pcm.sampleRate() > 0;
        assert pcm.pcmData() != null && pcm.pcmData().remaining() > 0;
        
        int channels = pcm.alFormat() == AL10.AL_FORMAT_MONO16 ? 1 : 2;
        double durationSeconds = pcm.pcmData().remaining() / (double) (Math.max(1, channels) * 2 * pcm.sampleRate());

        try {
            int id = al.callBlocking(() -> {
                int bufferId = AL10.alGenBuffers();
                if (bufferId == 0) {
                    throw new IllegalStateException("Failed to generate OpenAL buffer: " + AL10.alGetError());
                }
                AL10.alBufferData(bufferId, pcm.alFormat(), pcm.pcmData(), pcm.sampleRate());
                int err = AL10.alGetError();
                if (err != AL10.AL_NO_ERROR) {
                    AL10.alDeleteBuffers(bufferId);
                    throw new IllegalStateException("Failed to upload buffer data: " + err);
                }
                return bufferId;
            });
            bufferDurations.put(id, durationSeconds);
            return id;
        } finally {
            // The PCM allocation belongs to the loader, not to the queued AL task.
            // This still runs if shutdown rejects callBlocking before the task starts.
            pcm.free();
        }
    }

    /**
     * Creates a new stream decoder for the given sound path.
     * The caller is responsible for closing the decoder.
     */
    OggVorbisDecoder.StreamDecoder createStreamDecoder(ResourceLocation soundPath) {
        try {
            Resource r = mc.getResourceManager().getResource(soundPath).orElseThrow(
                    () -> new IllegalArgumentException("Missing sound resource: " + soundPath)
            );
            // StreamDecoder consumes the compressed file into native memory during construction,
            // so the resource stream can be closed immediately afterwards.
            try (InputStream in = r.open()) {
                return OggVorbisDecoder.createStreamDecoder(in, maxStreamedBytes);
            }
        } catch (Exception e) {
            GFBsAuralis.LOGGER.error("Failed to create stream decoder for: {}", soundPath, e);
            throw new RuntimeException("Failed to create stream decoder", e);
        }
    }
    
    CompletableFuture<OggVorbisDecoder.StreamDecoder> createStreamDecoderAsync(ResourceLocation soundPath) {
        return submitAsync(
                () -> createStreamDecoder(soundPath),
                decoder -> {
                    if (decoder != null) decoder.close();
                }
        );
    }

    /**
     * Builds a unique, processor-specific static buffer off the client/audio
     * thread. The returned buffer is not cached and must be deleted by its voice.
     */
    CompletableFuture<Integer> createProcessedBufferAsync(int baseBufferId, List<AudioProcessor> processors) {
        ResourceLocation soundPath = bufferToPath.get(baseBufferId);
        if (soundPath == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown base OpenAL buffer: " + baseBufferId)
            );
        }
        List<AudioProcessor> chain = List.copyOf(processors);
        return submitAsync(
                () -> loadProcessedBufferSync(soundPath, chain),
                id -> {
                    if (id != null && id != 0) deleteBuffersLater(new int[] {id});
                },
                new NonCancellingFuture<>()
        );
    }

    /**
     * Creates a set of empty OpenAL buffers for streaming.
     */
    int[] createStreamingBuffers(int count) {
        return al.callBlocking(() -> {
            int[] buffers = new int[count];
            try {
                for (int i = 0; i < count; i++) {
                    buffers[i] = AL10.alGenBuffers();
                    if (buffers[i] == 0) {
                        throw new IllegalStateException("Failed to gen buffer");
                    }
                }
                return buffers;
            } catch (Exception e) {
                for (int b : buffers) {
                    if (b != 0) AL10.alDeleteBuffers(b);
                }
                throw e;
            }
        });
    }

    void releaseBuffer(int bufferId) {
        // Only release cached static buffers here.
        // Streamed buffers are not cached and should be deleted manually via deleteBuffers.
        ResourceLocation soundPath = bufferToPath.get(bufferId);
        if (soundPath == null) {
            return;
        }

        Entry entry = cache.get(soundPath);
        if (entry == null || entry.bufferId() != bufferId) {
            bufferToPath.remove(bufferId);
            return;
        }

        int current;
        int left;
        do {
            current = entry.refs.get();
            if (current <= 0) {
                GFBsAuralis.LOGGER.debug("Ignoring duplicate release for OpenAL buffer {}", bufferId);
                return;
            }
            left = current - 1;
        } while (!entry.refs.compareAndSet(current, left));
        if (left == 0) {
            cache.remove(soundPath, entry);
            bufferToPath.remove(bufferId);
            bufferDurations.remove(bufferId);
            deleteBuffersLater(new int[] { bufferId });
        }
    }
    
    void deleteBuffers(int[] buffers) {
        if (buffers == null || buffers.length == 0) return;
        for (int id : buffers) bufferDurations.remove(id);
        deleteBuffersLater(buffers);
    }

    void clearAll() {
        for (Entry e : cache.values()) {
            int id = e.bufferId();
            deleteBuffersLater(new int[] { id });
        }
        cache.clear();
        bufferToPath.clear();
        bufferDurations.clear();
    }

    double getBufferDurationSeconds(int bufferId) {
        if (bufferId < 0) return 0.0;
        return bufferDurations.getOrDefault(bufferId, 0.0);
    }

    private int loadProcessedBufferSync(ResourceLocation soundPath, List<AudioProcessor> processors) {
        DecodedPcm pcm = decode(soundPath);
        if (pcm == null) throw new IllegalStateException("Failed to decode sound for PCM processing: " + soundPath);

        try {
            java.nio.ByteBuffer data = pcm.pcmData();
            int channels = pcm.alFormat() == AL10.AL_FORMAT_MONO16 ? 1 : 2;
            int frameBytes = Math.max(1, channels * 2);
            int position = data.position();
            int currentBytes = data.remaining();

            for (AudioProcessor processor : processors) {
                try {
                    if (!processor.isEnabled()) continue;
                    int newBytes;
                    synchronized (processor) {
                        processor.reset();
                        newBytes = processor.process(data, channels, pcm.sampleRate(), currentBytes);
                    }
                    // A faulty third-party processor may alter position/limit.
                    // Restore a valid window before validating its byte count so
                    // one bad DSP implementation cannot poison the remaining chain.
                    data.limit(data.capacity());
                    data.position(position);
                    int available = data.capacity() - position;
                    if (newBytes < 0 || newBytes > available || (newBytes % frameBytes) != 0) {
                        GFBsAuralis.LOGGER.error(
                                "Static audio processor {} returned invalid byte count {}; bypassing its size change",
                                safeProcessorId(processor), newBytes
                        );
                        data.limit(position + currentBytes);
                        continue;
                    }
                    currentBytes = newBytes;
                    data.limit(position + currentBytes);
                } catch (Throwable failure) {
                    data.limit(data.capacity());
                    data.position(position);
                    data.limit(position + currentBytes);
                    GFBsAuralis.LOGGER.error(
                            "Static audio processor {} failed; remaining chain will continue",
                            safeProcessorId(processor), failure
                    );
                }
            }
            if (currentBytes <= 0) throw new IllegalStateException("PCM processor chain produced an empty sound: " + soundPath);

            int id = al.callBlocking(() -> {
                int bufferId = AL10.alGenBuffers();
                if (bufferId == 0) throw new IllegalStateException("Failed to allocate processed OpenAL buffer");
                AL10.alBufferData(bufferId, pcm.alFormat(), data, pcm.sampleRate());
                int error = AL10.alGetError();
                if (error != AL10.AL_NO_ERROR) {
                    AL10.alDeleteBuffers(bufferId);
                    throw new IllegalStateException("Failed to upload processed buffer: " + error);
                }
                return bufferId;
            });
            bufferDurations.put(id, currentBytes / (double) (frameBytes * pcm.sampleRate()));
            return id;
        } finally {
            pcm.free();
        }
    }

    void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;

        asyncExecutor.shutdown();
        boolean terminated = false;
        try {
            terminated = asyncExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            GFBsAuralis.LOGGER.warn(
                    "Auralis async loaders did not stop within 5 seconds; cancelling {} pending load(s)",
                    pendingLoads.size()
            );
            for (CompletableFuture<?> pending : pendingLoads) {
                pending.cancel(false);
            }
            asyncExecutor.shutdownNow();
        }
        pendingLoads.clear();
    }

    private <T> CompletableFuture<T> submitAsync(Supplier<T> supplier, Consumer<T> cleanup) {
        return submitAsync(supplier, cleanup, new CompletableFuture<>());
    }

    private <T> CompletableFuture<T> submitAsync(
            Supplier<T> supplier,
            Consumer<T> cleanup,
            CompletableFuture<T> result
    ) {
        if (shuttingDown.get()) {
            result.completeExceptionally(new RejectedExecutionException("Auralis audio loader is shutting down"));
            return result;
        }

        pendingLoads.add(result);
        try {
            asyncExecutor.execute(() -> {
                T value = null;
                boolean produced = false;
                try {
                    if (shuttingDown.get()) {
                        throw new CancellationException("Auralis audio loader is shutting down");
                    }
                    value = supplier.get();
                    produced = true;
                    if (shuttingDown.get()) {
                        produced = false;
                        cleanup.accept(value);
                        result.completeExceptionally(new CancellationException("Auralis audio loader shut down during load"));
                    } else if (!result.complete(value)) {
                        produced = false;
                        cleanup.accept(value);
                    }
                } catch (Throwable failure) {
                    if (produced) {
                        try {
                            cleanup.accept(value);
                        } catch (Throwable cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    result.completeExceptionally(failure);
                } finally {
                    pendingLoads.remove(result);
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingLoads.remove(result);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private void deleteBuffersLater(int[] buffers) {
        try {
            al.submit(() -> {
                for (int id : buffers) {
                    if (id != 0) AL10.alDeleteBuffers(id);
                }
            });
        } catch (RuntimeException rejectedDuringShutdown) {
            GFBsAuralis.LOGGER.debug(
                    "Unable to queue OpenAL buffer deletion during shutdown; the owning context will reclaim it: {}",
                    rejectedDuringShutdown.getMessage()
            );
        }
    }

    private boolean tryIncrement(Entry entry) {
        int c;
        do {
            c = entry.refs.get();
            if (c <= 0 || c == Integer.MAX_VALUE) return false;
        } while (!entry.refs.compareAndSet(c, c + 1));
        return true;
    }

    private DecodedPcm decode(ResourceLocation soundPath) {
        try {
            Resource r = mc.getResourceManager().getResource(soundPath).orElseThrow(
                    () -> new IllegalArgumentException("Missing sound resource: " + soundPath)
            );
            try (InputStream in = r.open()) {
                return OggVorbisDecoder.decodeFully(in);
            } catch (Exception e) {
                GFBsAuralis.LOGGER.warn("Failed to decode OGG: {}", soundPath, e);
                throw new RuntimeException("Failed to decode OGG: " + soundPath + " ;E: " + e);
            }
        } catch (IllegalArgumentException e) {
            GFBsAuralis.LOGGER.warn("Missing sound resource: {} ;E: {}", soundPath, e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode OGG: " + soundPath + " ;E: " + e);
        }
    }

    private static String safeProcessorId(AudioProcessor processor) {
        try {
            return String.valueOf(processor.getId());
        } catch (Throwable ignored) {
            return processor.getClass().getName();
        }
    }
}
