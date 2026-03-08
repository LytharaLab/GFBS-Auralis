package org.mirage.gfbs.auralis;

/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2025-2029 Mirage-MC
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
import org.mirage.gfbs.auralis.utils.OggVorbisDecoder;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class SoundBufferCache {
    private record Entry(int bufferId, AtomicInteger refs) {}

    private final Minecraft mc;
    private final AuralisAL al;
    // streamedChunkSize/maxStreamedBytes are no longer needed here as we don't pre-decode
    private final Map<ResourceLocation, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Integer, ResourceLocation> bufferToPath = new ConcurrentHashMap<>();
    
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "Auralis-AsyncLoader");
                t.setDaemon(true);
                return t;
            }
    );

    SoundBufferCache(Minecraft mc, AuralisAL al, int streamedChunkSize, int maxStreamedBytes) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.al = Objects.requireNonNull(al, "al");
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
                        al.submit(() -> AL10.alDeleteBuffers(bufferId));
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

        Entry existing = cache.get(soundPath);
        if (existing != null && tryIncrement(existing)) {
            return CompletableFuture.completedFuture(existing.bufferId());
        }

        return CompletableFuture.supplyAsync(() -> {
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
                    al.submit(() -> AL10.alDeleteBuffers(bufferId));
                    return old.bufferId();
                }

                if (cache.replace(soundPath, old, newEntry)) {
                    bufferToPath.put(bufferId, soundPath);
                    return bufferId;
                }
            }
        }, asyncExecutor).exceptionally(ex -> {
            GFBsAuralis.LOGGER.error("Failed to acquire sound buffer asynchronously for: {}", soundPath, ex);
            return -1;
        });
    }

    private int loadBufferSync(ResourceLocation soundPath) {
        DecodedPcm pcm = decode(soundPath);
        if (pcm == null) {
            throw new RuntimeException("Failed to decode sound: " + soundPath);
        }
        assert pcm.sampleRate() > 0;
        assert pcm.pcmData() != null && pcm.pcmData().remaining() > 0;
        
        return al.callBlocking(() -> {
            try {
                int id = AL10.alGenBuffers();
                if (id == 0) {
                    throw new IllegalStateException("Failed to generate OpenAL buffer: " + AL10.alGetError());
                }
                AL10.alBufferData(id, pcm.alFormat(), pcm.pcmData(), pcm.sampleRate());
                int err = AL10.alGetError();
                if (err != AL10.AL_NO_ERROR) {
                    AL10.alDeleteBuffers(id);
                    throw new IllegalStateException("Failed to upload buffer data: " + err);
                }
                return id;
            } finally {
                pcm.free();
            }
        });
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
            InputStream in = r.open(); // StreamDecoder will close this stream
            return OggVorbisDecoder.createStreamDecoder(in);
        } catch (Exception e) {
            GFBsAuralis.LOGGER.error("Failed to create stream decoder for: {}", soundPath, e);
            throw new RuntimeException("Failed to create stream decoder", e);
        }
    }
    
    CompletableFuture<OggVorbisDecoder.StreamDecoder> createStreamDecoderAsync(ResourceLocation soundPath) {
        return CompletableFuture.supplyAsync(() -> createStreamDecoder(soundPath), asyncExecutor);
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

        int left = entry.refs.decrementAndGet();
        if (left == 0) {
            cache.remove(soundPath, entry);
            bufferToPath.remove(bufferId);
            al.submit(() -> AL10.alDeleteBuffers(bufferId));
        }
    }
    
    void deleteBuffers(int[] buffers) {
        if (buffers == null || buffers.length == 0) return;
        al.submit(() -> {
            for (int id : buffers) {
                if (id != 0) AL10.alDeleteBuffers(id);
            }
        });
    }

    void clearAll() {
        for (Entry e : cache.values()) {
            int id = e.bufferId();
            al.submit(() -> AL10.alDeleteBuffers(id));
        }
        cache.clear();
        bufferToPath.clear();
    }

    void shutdown() {
        asyncExecutor.shutdown();
    }

    private boolean tryIncrement(Entry entry) {
        int c;
        do {
            c = entry.refs.get();
            if (c <= 0) return false;
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
}
