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
    private static final long STOP_TIMEOUT_SECONDS = 15L;
    private static final int MAX_QUEUED_TASKS = 16_384;

    private static final class AlcExt {
        static final int ALC_HRTF_SOFT_ATTR = SOFTHRTF.ALC_HRTF_SOFT;

        static boolean setThreadContext(long ctx) {
            try {
                return EXTThreadLocalContext.alcSetThreadContext(ctx);
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
            this.contextAttributes = contextAttributes == null ? null : contextAttributes.clone();
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
                    new int[] { ALC_REFRESH, 60, ALC_SYNC, ALC_FALSE, 0 },
                    0L,
                    false,
                    true,
                    true
            );
        }

        public static Config defaultsWithHrtf(boolean enableHrtf) {
            int[] attrs = enableHrtf
                    ? new int[] {
                            AlcExt.ALC_HRTF_SOFT_ATTR, ALC_TRUE,
                            ALC_REFRESH, 60,
                            ALC_SYNC, ALC_FALSE,
                            0
                    }
                    : new int[] { ALC_REFRESH, 60, ALC_SYNC, ALC_FALSE, 0 };
            return new Config(
                    null,
                    "Auralis-OpenAL",
                    true,
                    attrs,
                    0L,
                    true,
                    true,
                    true
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

        default void reject(Throwable cause) {
        }
    }

    private static final class TaskWrapper implements ALTask {
        private final Runnable runnable;
        TaskWrapper(Runnable runnable) { this.runnable = runnable; }
        @Override public void run() { runnable.run(); }
    }

    private static final class FutureTaskWrapper<T> implements ALTask {
        private final Callable<T> callable;
        private final CompletableFuture<T> future;

        FutureTaskWrapper(Callable<T> callable, CompletableFuture<T> future) {
            this.callable = callable;
            this.future = future;
        }

        @Override
        public void run() throws Throwable {
            if (future.isDone()) return;
            try {
                future.complete(callable.call());
            } catch (VirtualMachineError | ThreadDeath fatal) {
                future.completeExceptionally(fatal);
                throw fatal;
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        @Override
        public void reject(Throwable cause) {
            future.completeExceptionally(cause);
        }
    }

    private static final ALTask STOP_TASK = () -> { };

    private final Config config;

    private final AtomicBoolean started;
    private final AtomicBoolean stopping;
    private final AtomicBoolean closed;

    private final BlockingQueue<ALTask> queue;
    private final Object queueGate;

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

        // One reserved slot guarantees shutdown can always enqueue STOP_TASK,
        // even after a producer burst fills the normal task budget.
        this.queue = new LinkedBlockingQueue<>(MAX_QUEUED_TASKS + 1);
        this.queueGate = new Object();

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
        try {
            t.start();
        } catch (Throwable startFailure) {
            fatalError.compareAndSet(null, startFailure);
            stopping.set(true);
            startLatch.countDown();
            stopLatch.countDown();
            if (startFailure instanceof RuntimeException runtime) throw runtime;
            if (startFailure instanceof Error error) throw error;
            throw new IllegalStateException("Unable to start OpenAL thread", startFailure);
        }

        awaitStartOrThrow();
    }

    public void stop() {
        if (!started.get()) {
            GFBsAuralis.LOGGER.debug("OpenAL not started, skipping stop");
            return;
        }
        synchronized (queueGate) {
            if (stopping.compareAndSet(false, true)) {
                GFBsAuralis.LOGGER.info("Stopping OpenAL thread: {}", config.threadName);
                // The gate prevents any task from being accepted behind this marker.
                // Everything already accepted is drained in FIFO order first.
                queue.offer(STOP_TASK);
            }
        }

        if (isOnALThread()) {
            // Waiting here would deadlock. The loop consumes STOP_TASK as soon as
            // the current callback returns.
            return;
        }

        try {
            if (!stopLatch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                GFBsAuralis.LOGGER.error(
                        "Timed out after {} seconds while stopping OpenAL thread {}; leaving daemon thread for JVM cleanup",
                        STOP_TIMEOUT_SECONDS,
                        config.threadName
                );
                return;
            }
            GFBsAuralis.LOGGER.info("OpenAL thread stopped successfully: {}", config.threadName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GFBsAuralis.LOGGER.warn("Interrupted while stopping OpenAL thread: {}", config.threadName);
            return;
        }

        Throwable fatal = fatalError.get();
        if (fatal != null) {
            GFBsAuralis.LOGGER.error("OpenAL thread terminated with fatal error", fatal);
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
        if (closed.get()) throw new RejectedExecutionException("AuralisAL is closed");
        if (!started.get()) throw new IllegalStateException("AuralisAL not started");
        if (stopping.get()) throw new RejectedExecutionException("AuralisAL is stopping");
        Throwable fatal = fatalError.get();
        if (fatal != null) {
            GFBsAuralis.LOGGER.error("AuralisAL has fatal error; OpenAL thread is not healthy: {}", String.valueOf(fatal));
            throw new IllegalStateException("OpenAL thread is not healthy", fatal);
        }
    }

    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        enqueue(new TaskWrapper(task));
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            enqueue(new FutureTaskWrapper<>(task, future));
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
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

        CompletableFuture<Void> f = submit(() -> {
                task.run();
                if (config.strictChecks) alCheck("after executeBlocking task");
                return null;
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

            while (true) {
                ALTask task;
                try {
                    if (config.idleWaitMillis <= 0L) {
                        task = queue.take();
                    } else {
                        task = queue.poll(config.idleWaitMillis, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    if (stopping.get()) continue;
                    GFBsAuralis.LOGGER.warn("OpenAL thread was interrupted unexpectedly; continuing");
                    continue;
                }
                if (task != null) {
                    if (task == STOP_TASK) break;
                    try {
                        task.run();
                        if (config.strictChecks) alCheck("after task");
                    } catch (VirtualMachineError | ThreadDeath fatal) {
                        throw fatal;
                    } catch (Throwable taskError) {
                        // A bad sound, processor, or callback must not kill the only
                        // OpenAL owner thread and strand every remaining voice.
                        GFBsAuralis.LOGGER.error("OpenAL task failed; task was isolated and audio processing will continue", taskError);
                    }
                }
            }
            GFBsAuralis.LOGGER.info("OpenAL thread stopping: {}", config.threadName);

        } catch (Throwable t) {
            fatalError.compareAndSet(null, t);
            GFBsAuralis.LOGGER.error("OpenAL thread encountered fatal error: {}", t.getMessage(), t);
            startLatch.countDown();
        } finally {
            startLatch.countDown();
            try {
                Throwable rejection = fatalError.get();
                if (rejection == null) {
                    rejection = new RejectedExecutionException("AuralisAL stopped before task execution");
                }
                ALTask abandoned;
                while ((abandoned = queue.poll()) != null) {
                    if (abandoned != STOP_TASK) abandoned.reject(rejection);
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
            attrsToUse = buildDefaultContextAttributes();
        }

        long ctx = alcCreateContext(dev, attrsToUse);
        if (ctx == 0L && attrsToUse != null) {
            int attributesError = alcGetError(dev);
            GFBsAuralis.LOGGER.warn(
                    "alcCreateContext failed with requested attributes (ALC error=0x{}), retrying with defaults",
                    Integer.toHexString(attributesError)
            );
            ctx = alcCreateContext(dev, (int[]) null);
        }
        if (ctx == 0L) {
            alcCloseDevice(dev);
            this.deviceHandle = 0L;
            throw new IllegalStateException("alcCreateContext failed");
        }
        this.contextHandle = ctx;

        boolean bound;
        if (this.alcCaps != null && this.alcCaps.ALC_EXT_thread_local_context) {
            // Clear any sticky ALC error left by an attribute fallback before
            // validating the context-binding call itself.
            alcGetError(dev);
            boolean ok = AlcExt.setThreadContext(ctx);
            if (ok) {
                bound = (alcGetError(dev) == ALC_NO_ERROR);
                if (!bound) {
                    // Do not destroy a context that may still be current on this
                    // thread after a driver reported a binding error.
                    AlcExt.setThreadContext(0L);
                }
            } else {
                bound = false;
            }
            this.usingThreadLocalContext = bound;
        } else {
            // The core ALC current context is process-global. Minecraft owns a
            // second OpenAL context on another thread, so using the core fallback
            // would let the two engines steal each other's context and corrupt
            // source/buffer state. OpenAL Soft (bundled by Minecraft) exposes EXT.
            bound = false;
            this.usingThreadLocalContext = false;
            GFBsAuralis.LOGGER.error("OpenAL device lacks ALC_EXT_thread_local_context; refusing unsafe shared-context startup");
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
        long ctx = this.contextHandle;

        // Unbind only if this instance successfully created/bound a context.
        // Calling the core unbind on a failed partial initialization could clear
        // another engine's process-global context.
        if (ctx != 0L) {
            if (this.usingThreadLocalContext
                    || (this.alcCaps != null && this.alcCaps.ALC_EXT_thread_local_context)) {
                if (!AlcExt.setThreadContext(0L)) {
                    GFBsAuralis.LOGGER.warn("Failed to clear Auralis thread-local OpenAL context during shutdown");
                }
            }
        }

        if (ctx != 0L) {
            if (config.destroyContextOnShutdown) {
                alcDestroyContext(ctx);
            }
            this.contextHandle = 0L;
        }

        // Each successful alcOpenDevice call owns a matching close. Auralis uses
        // its own context/device handle and never closes Minecraft's handle.
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

    private static int[] buildDefaultContextAttributes() {
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
        if (closed.get()) throw new IllegalStateException("AuralisAL is closed");
    }

    private void enqueue(ALTask task) {
        synchronized (queueGate) {
            ensureRunning();
            if (queue.size() >= MAX_QUEUED_TASKS || !queue.offer(task)) {
                throw new RejectedExecutionException(
                        "OpenAL task queue is full (limit=" + MAX_QUEUED_TASKS + ")"
                );
            }
        }
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
        try {
            created.start();
            return created;
        } catch (Throwable startupFailure) {
            GLOBAL.compareAndSet(created, null);
            try {
                created.close();
            } catch (Throwable cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
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
