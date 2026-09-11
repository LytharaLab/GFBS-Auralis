package org.lytharalab.gfbs.auralis.api;

import org.lytharalab.gfbs.auralis.network.ClientSoundRequestPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Optional validated client-to-server request API; disabled by default on servers. */
public final class AuralisClientApi {
    private static final Map<UUID, CompletableFuture<ClientRequestResult>> PENDING = new ConcurrentHashMap<>();
    private AuralisClientApi() { }

    public static AuralisOperation<ClientSoundInstance> create(String id, AuralisSoundSpec spec) {
        UUID requestId = UUID.randomUUID();
        ClientSoundRequestPacket packet = new ClientSoundRequestPacket(requestId,
                ClientSoundRequestPacket.Action.CREATE, id, spec, 0.0);
        CompletableFuture<ClientSoundInstance> result = new CompletableFuture<>();
        request(AuralisOperation.Kind.CREATE, packet).future().whenComplete((reply, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else if (!reply.applied()) result.completeExceptionally(new IllegalStateException(reply.detail()));
            else result.complete(new ClientSoundInstance(id));
        });
        return AuralisOperation.withId(requestId, AuralisOperation.Kind.CREATE, result);
    }

    static AuralisOperation<ClientRequestResult> request(AuralisOperation.Kind kind, ClientSoundRequestPacket packet) {
        CompletableFuture<ClientRequestResult> future = new CompletableFuture<>();
        PENDING.put(packet.requestId(), future);
        future.whenComplete((ignored, failure) -> PENDING.remove(packet.requestId(), future));
        try {
            NetworkHandler.CHANNEL.sendToServer(packet);
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
        return AuralisOperation.withId(packet.requestId(), kind, future);
    }

    public static void accept(ClientRequestResult result) {
        CompletableFuture<ClientRequestResult> future = PENDING.remove(result.requestId());
        if (future != null) future.complete(result);
    }

    public static void reset() {
        IllegalStateException failure = new IllegalStateException("Client connection closed");
        for (CompletableFuture<ClientRequestResult> future : PENDING.values()) future.completeExceptionally(failure);
        PENDING.clear();
    }
}
