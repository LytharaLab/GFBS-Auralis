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
import org.lwjgl.openal.AL10;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class OpenALSourcePool implements AutoCloseable {
    record SourceHandle(int sourceId) {}

    private final AuralisAL al;
    private final int maxSources;
    private final Object lock = new Object();
    private final ArrayDeque<SourceHandle> free = new ArrayDeque<>();
    private final Set<SourceHandle> inUse = new HashSet<>();
    private final Set<SourceHandle> allSources = new HashSet<>();
    final Map<SourceHandle, AuralisSoundInstanceImpl> sourceToInstance = new ConcurrentHashMap<>();
    private int generatedCount = 0;
    private int adaptiveMaxSources;
    
    // Metrics
    private int poolExhaustedCount = 0;
    private int sourcesRecycledCount = 0;
    private int allocFailedCount = 0;

    OpenALSourcePool(AuralisAL al, int maxSources) {
        this.al = Objects.requireNonNull(al, "al");
        this.maxSources = maxSources;
        this.adaptiveMaxSources = maxSources;
    }

    @Nullable SourceHandle acquire(AuralisSoundInstanceImpl instance) {
        SourceHandle h = tryAcquire(instance);
        if (h != null) return h;

        if (evictLowestPriorityNonLooping()) {
            h = tryAcquire(instance);
            if (h != null) return h;
        }

        poolExhaustedCount++;
        return null;
    }

    private @Nullable SourceHandle tryAcquire(AuralisSoundInstanceImpl instance) {
        SourceHandle reused;
        synchronized (lock) {
            reused = free.pollFirst();
            if (reused != null) {
                sourceToInstance.put(reused, instance);
                inUse.add(reused);
                return reused;
            }
            if (generatedCount >= adaptiveMaxSources) return null;
            generatedCount++;
        }

        int id;
        try {
            id = al.callBlocking(() -> {
                AL10.alGetError();
                int sid = AL10.alGenSources();
                int err = AL10.alGetError();
                return (sid != 0 && err == AL10.AL_NO_ERROR) ? sid : 0;
            });
        } catch (Throwable t) {
            id = 0;
        }

        if (id == 0) {
            synchronized (lock) {
                generatedCount = Math.max(0, generatedCount - 1);
                allocFailedCount++;
                adaptiveMaxSources = Math.min(adaptiveMaxSources, generatedCount);
                if (allocFailedCount == 1 || (allocFailedCount % 50) == 0) {
                    GFBsAuralis.LOGGER.warn(
                            "OpenAL source allocation failed (attempts={}, maxSources={}, effectiveMaxSources={}, generated={}). Consider lowering client config 'maxSources'.",
                            allocFailedCount, maxSources, adaptiveMaxSources, generatedCount
                    );
                }
            }
            return null;
        }

        SourceHandle created = new SourceHandle(id);
        synchronized (lock) {
            allSources.add(created);
            sourceToInstance.put(created, instance);
            inUse.add(created);
        }
        return created;
    }

    private boolean evictLowestPriorityNonLooping() {
        SourceHandle lowestPrioritySource = null;
        int lowestPriority = Integer.MAX_VALUE;

        synchronized (lock) {
            if (inUse.isEmpty()) return false;
            for (SourceHandle handle : inUse) {
                AuralisSoundInstanceImpl instance = sourceToInstance.get(handle);
                if (instance != null && !instance.isLooping()) {
                    int priority = instance.getPriority();
                    if (priority < lowestPriority) {
                        lowestPriority = priority;
                        lowestPrioritySource = handle;
                    }
                }
            }
        }

        if (lowestPrioritySource == null) return false;
        AuralisSoundInstanceImpl instance = sourceToInstance.get(lowestPrioritySource);
        if (instance == null) return false;
        try {
            instance.onEvicted();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void release(SourceHandle h) {
        synchronized (lock) {
            if (!inUse.remove(h)) return;
            free.addLast(h);
        }
    }

    void tickRecycleEndedSources() {
        List<SourceHandle> candidates = new ArrayList<>();
        synchronized (lock) {
            for (SourceHandle handle : inUse) {
                if (!sourceToInstance.containsKey(handle)) {
                    candidates.add(handle);
                }
            }
        }
        if (candidates.isEmpty()) return;

        List<SourceHandle> stopped = al.callBlocking(() -> {
            List<SourceHandle> out = new ArrayList<>();
            for (SourceHandle h : candidates) {
                int state = AL10.alGetSourcei(h.sourceId(), AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_STOPPED) {
                    out.add(h);
                }
            }
            return out;
        });

        if (stopped.isEmpty()) return;
        synchronized (lock) {
            for (SourceHandle h : stopped) {
                if (inUse.remove(h)) {
                    free.addLast(h);
                    sourcesRecycledCount++;
                }
            }
        }
    }
    
    // Metrics access methods
    public int getMaxSources() {
        return maxSources;
    }
    
    public int getFreeSources() {
        synchronized (lock) {
            return free.size();
        }
    }
    
    public int getInUseSources() {
        synchronized (lock) {
            return inUse.size();
        }
    }
    
    public int getPoolExhaustedCount() {
        return poolExhaustedCount;
    }
    
    public int getSourcesRecycledCount() {
        return sourcesRecycledCount;
    }

    @Override
    public void close() {
        List<SourceHandle> all;
        synchronized (lock) {
            all = new ArrayList<>(allSources);
            allSources.clear();
            inUse.clear();
            free.clear();
            generatedCount = 0;
        }
        al.executeBlocking(() -> {
            for (SourceHandle h : all) {
                AL10.alSourceStop(h.sourceId());
                AL10.alDeleteSources(h.sourceId());
            }
        });
    }
}
