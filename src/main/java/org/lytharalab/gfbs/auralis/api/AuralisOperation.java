package org.lytharalab.gfbs.auralis.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** A lifecycle operation whose completion represents the real committed effect. */
public final class AuralisOperation<T> {
    public enum Kind { CREATE, PLAY, PAUSE, STOP, SEEK, UPDATE, SET_GLOBAL, DISPOSE, SYNCHRONIZE }

    private final UUID id;
    private final Kind kind;
    private final CompletableFuture<T> future;

    private AuralisOperation(UUID id, Kind kind, CompletableFuture<T> future) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.future = Objects.requireNonNull(future, "future");
    }

    public static <T> AuralisOperation<T> from(Kind kind, CompletionStage<T> stage) {
        return withId(UUID.randomUUID(), kind, stage);
    }

    public static <T> AuralisOperation<T> withId(UUID id, Kind kind, CompletionStage<T> stage) {
        return new AuralisOperation<>(id, kind, stage.toCompletableFuture());
    }

    public static <T> AuralisOperation<T> completed(Kind kind, T value) {
        return from(kind, CompletableFuture.completedFuture(value));
    }

    public static <T> AuralisOperation<T> failed(Kind kind, Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return from(kind, future);
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public CompletableFuture<T> future() { return future; }
    public boolean isDone() { return future.isDone(); }
    public boolean cancel(boolean mayInterruptIfRunning) { return future.cancel(mayInterruptIfRunning); }

    public AuralisOperation<T> onComplete(BiConsumer<? super T, ? super Throwable> action) {
        future.whenComplete(action);
        return this;
    }

    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> mapper) {
        return future.thenApply(mapper);
    }

    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> mapper) {
        return future.thenCompose(mapper);
    }
}
