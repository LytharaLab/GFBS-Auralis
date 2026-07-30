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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.openal.*;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.AL10.*;

@Mod.EventBusSubscriber(
        modid = GFBsAuralis.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class AuralisAL implements AutoCloseable {
    public static final AtomicReference<AuralisAL> GLOBAL = new AtomicReference<>(null);
    private static final int ALC_MONO_SOURCES_ATTR = 0x1010;
    private static final int ALC_STEREO_SOURCES_ATTR = 0x1011;

    private static final class AlcExt {
        static final boolean HAS_THREAD_LOCAL_CONTEXT;
        static final Method ALC_SET_THREAD_CONTEXT;
        static final int ALC_HRTF_SOFT_ATTR;

        static {
            Method setThreadCtx = null;
            boolean hasTlc = false;

            int hrtfAttr = 0;

            String[] tlcClassCandidates = new String[] {
                    "org.lwjgl.openal.ALC_EXT_thread_local_context",
                    "org.lwjgl.openal.ALCEXTThreadLocalContext"
            };
            for (String cn : tlcClassCandidates) {
                try {
                    Class<?> c = Class.forName(cn);
                    setThreadCtx = c.getMethod("alcSetThreadContext", long.class);
                    hasTlc = true;
                    break;
                } catch (Throwable ignore) {
                }
            }

            String[] hrtfClassCandidates = new String[] {
                    "org.lwjgl.openal.ALC_SOFT_HRTF",
                    "org.lwjgl.openal.ALCSOFTHRTF"
            };
            for (String cn : hrtfClassCandidates) {
                try {
                    Class<?> c = Class.forName(cn);
                    Field f = c.getField("ALC_HRTF_SOFT");
                    Object v = f.get(null);
                    if (v instanceof Integer) {
                        hrtfAttr = (Integer) v;
                        break;
                    }
                } catch (Throwable ignore) {
                }
            }

            ALC_SET_THREAD_CONTEXT = setThreadCtx;
            HAS_THREAD_LOCAL_CONTEXT = hasTlc;
            ALC_HRTF_SOFT_ATTR = hrtfAttr;
        }

        static boolean setThreadContext(long ctx) {
            if (!HAS_THREAD_LOCAL_CONTEXT || ALC_SET_THREAD_CONTEXT == null) return false;
            try {
                ALC_SET_THREAD_CONTEXT.invoke(null, ctx);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public static final class Config {
        /** OpenAL device name, or null for default device. */
        public final String deviceName;

        /** Thread name for the OpenAL thread. */
        public final String threadName;

        /** If true, make the OpenAL thread a daemon thread. */
        public final boolean daemonThread;

        /**
         * ALC context attributes array (ALC10.alcCreateContext attrs), or null for default.
         * Example:
         *   new int[] { ALC_REFRESH, 60, ALC_SYNC, ALC_FALSE, 0 }
         */
        public final int[] contextAttributes;

        /**
         * Poll wait time for loop when no tasks are queued.
         * 0 = use BlockingQueue.take() (lowest CPU).
         */
        public final long idleWaitMillis;

        /**
         * If true, do extra checks / throw more aggressively.
         */
        public final boolean strictChecks;

        public final boolean destroyContextOnShutdown;

        public final boolean closeDeviceOnShutdown;

        public Config(
                String deviceName,
                String threadName,
                boolean daemonThread,
                int[] contextAttributes,
                long idleWaitMillis,
                boolean strictChecks,
                boolean destroyContextOnShutdown,
                boolean closeDeviceOnShutdown
        ) {
            this.deviceName = deviceName;
            this.threadName = Objects.requireNonNullElse(threadName, "Auralis-OpenAL");
            this.daemonThread = daemonThread;
            this.contextAttributes = contextAttributes;
            this.idleWaitMillis = Math.max(0L, idleWaitMillis);
            this.strictChecks = strictChecks;
            this.destroyContextOnShutdown = destroyContextOnShutdown;
            this.closeDeviceOnShutdown = closeDeviceOnShutdown;
        }

        public static Config defaults() {
            return new Config(
                    null,
                    "Auralis-OpenAL",
                    true,
                    null,
                    0L,
                    false,
                    false,
                    false
            );
        }

        public static Config defaultsWithHrtf(boolean enableHrtf) {
            int[] attrs = null;
            if (enableHrtf && AlcExt.ALC_HRTF_SOFT_ATTR != 0) {
                attrs = new int[]{ AlcExt.ALC_HRTF_SOFT_ATTR, ALC_TRUE, 0 };
            }
            return new Config(
                    null,
                    "Auralis-OpenAL",
                    true,
                    attrs,
                    0L,
                    true,
                    false,
                    false
            );
        }
    }

    public record SourceBudget(int monoSources, int stereoSources) {
        public int totalSources() {
            return Math.max(0, monoSources) + Math.max(0, stereoSources);
        }

        public boolean isAvailable() {
            return monoSources >= 0 || stereoSources >= 0;
        }
    }

    private interface ALTask {
        void run() throws Throwable;
    }

    private static final class TaskWrapper implements ALTask {
        private final Runnable runnable;
        TaskWrapper(Runnable runnable) { this.runnable = runnable; }
        @Override public void run() { runnable.run(); }
    }

    private final Config config;

    private final AtomicBoolean started;
    private final AtomicBoolean stopping;
    private final AtomicBoolean closed;

    private final BlockingQueue<ALTask> queue;

    private final CountDownLatch startLatch;
    private final CountDownLatch stopLatch;

    private volatile Thread alThread;

    private volatile long deviceHandle;  // ALCdevice*
    private volatile long contextHandle; // ALCcontext*

    private volatile ALCCapabilities alcCaps;
    private volatile ALCapabilities alCaps;

    private volatile boolean usingThreadLocalContext;

    private final AtomicReference<Throwable> fatalError;

    public AuralisAL(Config config) {
        this.config = Objects.requireNonNull(config, "config");

        this.started = new AtomicBoolean(false);
        this.stopping = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);

        this.queue = new LinkedBlockingQueue<>();

        this.startLatch = new CountDownLatch(1);
        this.stopLatch = new CountDownLatch(1);

        this.deviceHandle = 0L;
        this.contextHandle = 0L;

        this.alcCaps = null;
        this.alCaps = null;

        this.usingThreadLocalContext = false;

        this.fatalError = new AtomicReference<>(null);
    }

    public void start() {
        ensureNotClosed();

        if (!started.compareAndSet(false, true)) {
            GFBsAuralis.LOGGER.debug("OpenAL already started, waiting for initialization");
            awaitStartOrThrow();
            return;
        }

        Thread t = new Thread(this::threadMain, config.threadName);
        t.setDaemon(config.daemonThread);
        this.alThread = t;
        GFBsAuralis.LOGGER.info("Launching OpenAL thread: {}", config.threadName);
        t.start();

        awaitStartOrThrow();
    }

    public void stop() {
        if (!started.get()) {
            GFBsAuralis.LOGGER.debug("OpenAL not started, skipping stop");
            return;
        }
        if (stopping.compareAndSet(false, true)) {
            GFBsAuralis.LOGGER.info("Stopping OpenAL thread: {}", config.threadName);
            queue.offer(() -> {});
            Thread t = this.alThread;
            if (t != null) t.interrupt();
        }

        try {
            stopLatch.await();
            GFBsAuralis.LOGGER.info("OpenAL thread stopped successfully: {}", config.threadName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while stopping OpenAL thread", e);
        }

        Throwable fatal = fatalError.get();
        if (fatal != null) {
            throw new RuntimeException("OpenAL thread terminated with fatal error", fatal);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            GFBsAuralis.LOGGER.info("Closing AuralisAL instance");
            stop();
        } else {
            GFBsAuralis.LOGGER.debug("AuralisAL already closed, skipping close");
        }
    }

    public boolean isStarted() { return started.get(); }
    public boolean isStopping() { return stopping.get(); }
    public boolean isClosed() { return closed.get(); }

    public boolean isOnALThread() {
        Thread t = this.alThread;
        return t != null && Thread.currentThread() == t;
    }

    public void ensureRunning() {
        ensureNotClosed();
        if (!started.get()) throw new IllegalStateException("AuralisAL not started");
        Throwable fatal = fatalError.get();
        if (fatal != null) {
            GFBsAuralis.LOGGER.error("AuralisAL has fatal error; OpenAL thread is not healthy: {}", String.valueOf(fatal));
            throw new IllegalStateException("OpenAL thread is not healthy", fatal);
        }
    }

    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureRunning();
        queue.offer(new TaskWrapper(task));
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        ensureRunning();
        CompletableFuture<T> future = new CompletableFuture<>();
        queue.offer(() -> {
            try {
                T v = task.call();
                future.complete(v);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void executeBlocking(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureRunning();

        if (isOnALThread()) {
            task.run();
            if (config.strictChecks) alCheck("after executeBlocking on AL thread");
            return;
        }

        CompletableFuture<Void> f = new CompletableFuture<>();
        queue.offer(() -> {
            try {
                task.run();
                if (config.strictChecks) alCheck("after executeBlocking task");
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        joinFuture(f);
    }

    public <T> T callBlocking(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        ensureRunning();

        if (isOnALThread()) {
            try {
                T result = task.call();
                if (config.strictChecks) alCheck("after callBlocking on AL thread");
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CompletableFuture<T> f = submit(task);
        return joinFuture(f);
    }

    public long deviceHandle() { ensureRunning(); return deviceHandle; }
    public long contextHandle() { ensureRunning(); return contextHandle; }
    public ALCCapabilities alcCapabilities() { ensureRunning(); return alcCaps; }
    public ALCapabilities alCapabilities() { ensureRunning(); return alCaps; }

    public SourceBudget querySourceBudget() {
        ensureRunning();
        return callBlocking(this::querySourceBudgetOnALThread);
    }

    public int recommendSourcePoolLimit(int configuredMaxSources, int reserveSourcesForVanilla) {
        ensureRunning();
        return callBlocking(() -> recommendSourcePoolLimitOnALThread(configuredMaxSources, reserveSourcesForVanilla));
    }

    private int recommendSourcePoolLimitOnALThread(int configuredMaxSources, int reserveSourcesForVanilla) {
        int reserve = Math.max(0, reserveSourcesForVanilla);
        int requestedPoolSize = configuredMaxSources > 0 ? configuredMaxSources : 160;
        int effectivePoolSize = Math.max(1, requestedPoolSize - reserve);
        SourceBudget budget = querySourceBudgetOnALThread();
        if (!budget.isAvailable()) {
            return effectivePoolSize;
        }

        int totalDeviceSources = budget.totalSources();
        if (totalDeviceSources <= 0) {
            return effectivePoolSize;
        }

        int safetyHeadroom = Math.max(8, Math.min(32, totalDeviceSources / 10));
        int deviceAwareEffectiveLimit = Math.max(1, totalDeviceSources - reserve - safetyHeadroom);
        return Math.min(effectivePoolSize, deviceAwareEffectiveLimit);
    }

    private SourceBudget querySourceBudgetOnALThread() {
        long dev = this.deviceHandle;
        if (dev == 0L) {
            return new SourceBudget(-1, -1);
        }
        int mono = queryDeviceInt(dev, ALC_MONO_SOURCES_ATTR);
        int stereo = queryDeviceInt(dev, ALC_STEREO_SOURCES_ATTR);
        return new SourceBudget(mono, stereo);
    }

    private int queryDeviceInt(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(1);
            alcGetError(device);
            alcGetIntegerv(device, param, values);
            int err = alcGetError(device);
            if (err != ALC_NO_ERROR) {
                return -1;
            }
            return values.get(0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private void threadMain() {
        GFBsAuralis.LOGGER.info("Starting OpenAL thread: {}", config.threadName);
        try {
            initOpenAL();
            GFBsAuralis.LOGGER.info("OpenAL initialized successfully on device: {}", config.deviceName != null ? config.deviceName : "default");
            startLatch.countDown();

            while (!stopping.get()) {
                ALTask task;
                if (config.idleWaitMillis <= 0L) {
                    task = queue.take();
                } else {
                    task = queue.poll(config.idleWaitMillis, TimeUnit.MILLISECONDS);
                }
                if (task != null) {
                    task.run();
                    if (config.strictChecks && !stopping.get()) alCheck("after task");
                }
            }
            GFBsAuralis.LOGGER.info("OpenAL thread stopping: {}", config.threadName);

        } catch (Throwable t) {
            fatalError.compareAndSet(null, t);
            GFBsAuralis.LOGGER.error("OpenAL thread encountered fatal error: {}", t.getMessage(), t);
            startLatch.countDown();
        } finally {
            try {
                while (!queue.isEmpty()) {
                    ALTask task = queue.poll();
                    if (task != null) {
                        try {
                            task.run();
                        } catch (Throwable t2) {
                            GFBsAuralis.LOGGER.warn("Error executing queued task during shutdown: {}", t2.getMessage());
                        }
                    }
                }
                destroyOpenAL();
                GFBsAuralis.LOGGER.info("OpenAL resources destroyed successfully");
            } catch (Throwable t2) {
                fatalError.compareAndSet(null, t2);
                GFBsAuralis.LOGGER.error("Error destroying OpenAL resources: {}", t2.getMessage(), t2);
            } finally {
                stopLatch.countDown();
            }
        }
    }

    private void initOpenAL() {
        // ---- Device open (with safe fallback) ----
        long dev = alcOpenDevice(config.deviceName);
        if (dev == 0L && config.deviceName != null) {
            GFBsAuralis.LOGGER.warn("alcOpenDevice failed for '{}', falling back to default device", config.deviceName);
            dev = alcOpenDevice((String) null);
        }
        if (dev == 0L) {
            String devices = "<unavailable>";
            try { devices = enumerateDevicesString(); } catch (Throwable ignore) {}
            throw new IllegalStateException("alcOpenDevice failed (deviceName=" + config.deviceName + "). Available devices=" + devices);
        }
        this.deviceHandle = dev;

        this.alcCaps = ALC.createCapabilities(dev);

        // ---- Context create (prefer thread-local if available) ----
        int[] attrsToUse = config.contextAttributes;
        if (attrsToUse == null) {
            attrsToUse = buildDefaultContextAttributes(this.alcCaps);
        }

        long ctx = alcCreateContext(dev, attrsToUse);
        if (ctx == 0L && attrsToUse != null) {
            GFBsAuralis.LOGGER.warn("alcCreateContext failed with requested attributes, retrying with defaults");
            ctx = alcCreateContext(dev, (int[]) null);
        }
        if (ctx == 0L) {
            alcCloseDevice(dev);
            this.deviceHandle = 0L;
            throw new IllegalStateException("alcCreateContext failed");
        }
        this.contextHandle = ctx;

        boolean bound;
        if (this.alcCaps != null && this.alcCaps.ALC_EXT_thread_local_context && AlcExt.HAS_THREAD_LOCAL_CONTEXT) {
            boolean ok = AlcExt.setThreadContext(ctx);
            if (ok) {
                bound = (alcGetError(dev) == ALC_NO_ERROR);
            } else {
                bound = false;
            }
            this.usingThreadLocalContext = bound;
        } else {
            bound = alcMakeContextCurrent(ctx);
            this.usingThreadLocalContext = false;
        }

        if (!bound) {
            alcDestroyContext(ctx);
            alcCloseDevice(dev);
            this.contextHandle = 0L;
            this.deviceHandle = 0L;
            throw new IllegalStateException("Failed to bind OpenAL context to thread");
        }

        this.alCaps = AL.createCapabilities(this.alcCaps);

        AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);

        AL10.alDopplerFactor(1.0f);
        AL11.alSpeedOfSound(343.3f);

        alGetError();

        if (config.strictChecks) {
            alcCheck("post-init");
            alCheck("post-init");
        }
    }

    private void destroyOpenAL() {
        long dev = this.deviceHandle;

        // Unbind
        if (this.usingThreadLocalContext && AlcExt.HAS_THREAD_LOCAL_CONTEXT) {
            try {
                AlcExt.setThreadContext(0L);
            } catch (Throwable ignore) {
                alcMakeContextCurrent(0L);
            }
        } else {
            alcMakeContextCurrent(0L);
        }

        long ctx = this.contextHandle;
        if (ctx != 0L) {
            if (config.destroyContextOnShutdown) {
                alcDestroyContext(ctx);
            }
            this.contextHandle = 0L;
        }

        // Do NOT close the device if we are sharing the default device with Minecraft.
        // OpenAL Soft may return the same device handle for multiple alcOpenDevice calls.
        // If we close it, Minecraft's sound system will crash during its shutdown because
        // its device handle becomes invalid.
        // Since we are shutting down anyway, the OS will reclaim the device resources.
        if (dev != 0L) {
            if (config.closeDeviceOnShutdown) {
                alcCloseDevice(dev);
            }
            this.deviceHandle = 0L;
        }

        this.alCaps = null;
        this.alcCaps = null;
        this.usingThreadLocalContext = false;
    }

    public void alcCheck(String where) {
        long dev = this.deviceHandle;
        if (dev == 0L) return;
        int err = alcGetError(dev);
        if (err != ALC_NO_ERROR) {
            throw new IllegalStateException("ALC error at " + where + ": 0x" + Integer.toHexString(err));
        }
    }

    public void alCheck(String where) {
        int err = alGetError();
        if (err == AL_NO_ERROR) return;

        String errorMsg = getALErrorString(err);
        String msg = "AL error at " + where + ": " + errorMsg + " (0x" + Integer.toHexString(err) + ")";

        if (config.strictChecks) GFBsAuralis.LOGGER.error(msg);
    }

    private static int[] buildDefaultContextAttributes(ALCCapabilities caps) {
        if (caps != null && caps.ALC_SOFT_HRTF && AlcExt.ALC_HRTF_SOFT_ATTR != 0) {
            return new int[] {
                    AlcExt.ALC_HRTF_SOFT_ATTR, ALC_TRUE,
                    ALC_REFRESH, 60,
                    ALC_SYNC, ALC_FALSE,
                    0
            };
        }
        return new int[] {
                ALC_REFRESH, 60,
                ALC_SYNC, ALC_FALSE,
                0
        };
    }

    private static String enumerateDevicesString() {
        int spec = ALC11.ALC_ALL_DEVICES_SPECIFIER;
        String raw;
        try {
            raw = alcGetString(0L, spec);
        } catch (Throwable t) {
            raw = alcGetString(0L, ALC_DEVICE_SPECIFIER);
        }
        if (raw == null || raw.isEmpty()) return "<unavailable>";
        String[] parts = raw.split("\\u0000");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.length() == 0 ? "<unavailable>" : sb.toString();
    }

    private static String getALErrorString(int errorCode) {
        switch (errorCode) {
            case AL_NO_ERROR: return "AL_NO_ERROR (没有错误)";
            case AL_INVALID_NAME: return "AL_INVALID_NAME (无效的名称)";
            case AL_INVALID_ENUM: return "AL_INVALID_ENUM (无效的枚举值)";
            case AL_INVALID_VALUE: return "AL_INVALID_VALUE (无效的参数值)";
            case AL_INVALID_OPERATION: return "AL_INVALID_OPERATION (无效的操作)";
            case AL_OUT_OF_MEMORY: return "AL_OUT_OF_MEMORY (内存不足)";
            default: return "未知错误: 0x" + Integer.toHexString(errorCode);
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) GFBsAuralis.LOGGER.warn("AuralisAL is closed");
    }

    private void awaitStartOrThrow() {
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting OpenAL thread", e);
        }

        Throwable fatal = fatalError.get();
        if (fatal != null) throw new RuntimeException("OpenAL initialization failed", fatal);

        if (deviceHandle == 0L || contextHandle == 0L || alcCaps == null || alCaps == null) {
            throw new IllegalStateException("OpenAL started but some handles/capabilities are missing");
        }
    }

    private static <T> T joinFuture(CompletableFuture<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for AL task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException("AL task failed", cause);
        }
    }

    public static AuralisAL createAndStartGlobal(Config config) {
        Objects.requireNonNull(config, "config");

        AuralisAL created = new AuralisAL(config);
        if (!GLOBAL.compareAndSet(null, created)) {
            GFBsAuralis.LOGGER.debug("Global AuralisAL instance already exists, returning existing instance");
            return GLOBAL.get();
        }

        GFBsAuralis.LOGGER.info("Creating and starting global AuralisAL instance");
        created.start();
        return created;
    }

    public static void stopAndClearGlobal() {
        AuralisAL inst = GLOBAL.getAndSet(null);
        if (inst != null) {
            GFBsAuralis.LOGGER.info("Stopping and clearing global AuralisAL instance");
            inst.close();
        } else {
            GFBsAuralis.LOGGER.debug("No global AuralisAL instance to stop, skipping");
        }
    }
}
