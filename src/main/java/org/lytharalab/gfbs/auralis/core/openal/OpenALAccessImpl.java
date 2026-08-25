package org.lytharalab.gfbs.auralis.core.openal;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.lytharalab.gfbs.auralis.AuralisAL;
import org.lytharalab.gfbs.auralis.api.openal.OpenALAccess;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public final class OpenALAccessImpl implements OpenALAccess {
    private final AuralisAL al;
    private final boolean efxSupported;
    private final int maxAuxiliarySends;

    public OpenALAccessImpl(AuralisAL al) {
        this.al = Objects.requireNonNull(al, "al");
        boolean supported;
        int sends;
        try {
            supported = al.callBlocking(() -> al.alcCapabilities().ALC_EXT_EFX);
            sends = supported ? al.callBlocking(this::queryMaxAuxiliarySendsOnALThread) : 0;
        } catch (Throwable ignored) {
            supported = false;
            sends = 0;
        }
        this.efxSupported = supported;
        this.maxAuxiliarySends = Math.max(0, sends);
    }

    @Override
    public boolean isAvailable() {
        return al.isStarted() && !al.isStopping() && !al.isClosed();
    }

    @Override public boolean isOnAudioThread() { return al.isOnALThread(); }
    @Override public boolean isEfxSupported() { return efxSupported; }
    @Override public int getMaxAuxiliarySends() { return maxAuxiliarySends; }

    @Override
    public void execute(Runnable operation) {
        al.executeBlocking(Objects.requireNonNull(operation, "operation"));
    }

    @Override
    public <T> T call(Callable<T> operation) {
        return al.callBlocking(Objects.requireNonNull(operation, "operation"));
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> operation) {
        return al.submit(Objects.requireNonNull(operation, "operation"));
    }

    private int queryMaxAuxiliarySendsOnALThread() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer result = stack.callocInt(1);
            ALC10.alcGetIntegerv(al.deviceHandle(), EXTEfx.ALC_MAX_AUXILIARY_SENDS, result);
            return result.get(0);
        }
    }
}
