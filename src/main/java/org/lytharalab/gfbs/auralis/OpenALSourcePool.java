package org.lytharalab.gfbs.auralis;
/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2026 LytharaLab
 *
 * This program is licensed under the MIT License.
 */
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pool of scarce physical OpenAL sources.
 *
 * <p>Since Auralis 2.1 a source is only a physical rendering resource. Logical
 * sound instances are allowed to outnumber this pool and are virtualized by
 * {@link AuralisVoiceManager}. The pool therefore never destroys another sound
 * instance to satisfy an allocation request.</p>
 */
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
        this.maxSources = Math.max(1, maxSources);
        this.adaptiveMaxSources = this.maxSources;
    }

    @Nullable SourceHandle acquire(AuralisSoundInstanceImpl instance) {
        SourceHandle handle = tryAcquire(instance);
        if (handle == null) {
            synchronized (lock) {
                poolExhaustedCount++;
            }
        }
        return handle;
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
                // Once a driver refuses a source, stop hammering it every tick.
                adaptiveMaxSources = Math.max(1, Math.min(adaptiveMaxSources, generatedCount));
                if (allocFailedCount == 1 || (allocFailedCount % 50) == 0) {
                    GFBsAuralis.LOGGER.warn(
                            "OpenAL source allocation failed (attempts={}, maxSources={}, effectiveMaxSources={}, generated={}). " +
                                    "Auralis will virtualize excess voices.",
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

    void release(SourceHandle handle) {
        if (handle == null) return;
        synchronized (lock) {
            sourceToInstance.remove(handle);
            if (!inUse.remove(handle)) return;
            free.addLast(handle);
            sourcesRecycledCount++;
        }
    }

    /**
     * Defensive cleanup for a source whose owner disappeared unexpectedly. Normal
     * 2.1 voice transitions release sources explicitly, so this should be rare.
     */
    void tickRecycleEndedSources() {
        List<SourceHandle> orphaned = new ArrayList<>();
        synchronized (lock) {
            for (SourceHandle handle : inUse) {
                if (!sourceToInstance.containsKey(handle)) {
                    orphaned.add(handle);
                }
            }
        }
        if (orphaned.isEmpty()) return;

        al.executeBlocking(() -> {
            for (SourceHandle handle : orphaned) {
                try {
                    AL10.alSourceStop(handle.sourceId());
                    AL10.alSourcei(handle.sourceId(), AL10.AL_BUFFER, 0);
                } catch (Throwable ignored) {
                }
            }
        });

        synchronized (lock) {
            for (SourceHandle handle : orphaned) {
                if (inUse.remove(handle)) {
                    free.addLast(handle);
                    sourcesRecycledCount++;
                }
            }
        }
    }

    int getMaxSources() {
        return maxSources;
    }

    int getEffectiveMaxSources() {
        synchronized (lock) {
            return Math.max(1, adaptiveMaxSources);
        }
    }

    int getGeneratedSources() {
        synchronized (lock) {
            return generatedCount;
        }
    }

    int getFreeSources() {
        synchronized (lock) {
            return free.size();
        }
    }

    int getInUseSources() {
        synchronized (lock) {
            return inUse.size();
        }
    }

    int getPoolExhaustedCount() {
        synchronized (lock) {
            return poolExhaustedCount;
        }
    }

    int getSourcesRecycledCount() {
        synchronized (lock) {
            return sourcesRecycledCount;
        }
    }

    @Override
    public void close() {
        List<SourceHandle> all;
        synchronized (lock) {
            all = new ArrayList<>(allSources);
            allSources.clear();
            inUse.clear();
            free.clear();
            sourceToInstance.clear();
            generatedCount = 0;
        }
        al.executeBlocking(() -> {
            for (SourceHandle handle : all) {
                try {
                    AL10.alSourceStop(handle.sourceId());
                    AL10.alSourcei(handle.sourceId(), AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(handle.sourceId());
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
