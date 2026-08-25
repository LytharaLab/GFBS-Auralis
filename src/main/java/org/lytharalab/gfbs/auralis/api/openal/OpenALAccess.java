package org.lytharalab.gfbs.auralis.api.openal;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Controlled access to the active OpenAL context for Auralis plugins.
 *
 * <p>The API intentionally exposes execution, not ownership. It never exposes
 * ALC device/context handles and cannot start, stop or replace AuralisAL.</p>
 */
public interface OpenALAccess {
    boolean isAvailable();

    boolean isOnAudioThread();

    boolean isEfxSupported();

    int getMaxAuxiliarySends();

    void execute(Runnable operation);

    <T> T call(Callable<T> operation);

    <T> CompletableFuture<T> submit(Callable<T> operation);
}
