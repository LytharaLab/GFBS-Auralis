package org.lytharalab.gfbs.auralis.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.Collection;
import java.util.Optional;

/** Entry point for server-authoritative audio. This class never touches OpenAL. */
public final class AuralisServerApi {
    private AuralisServerApi() { }

    public static AuralisOperation<ServerSoundInstance> create(
            MinecraftServer server, String id, AuralisSoundSpec spec, Collection<ServerPlayer> audience) {
        return AuralisServerManager.create(server, id, spec, audience);
    }

    /** Makes this instance visible to every current and future player. Server-only by construction. */
    public static AuralisOperation<ServerOperationResult> setGlobal(ServerSoundInstance instance) {
        return AuralisServerManager.setGlobal(instance, true);
    }

    public static AuralisOperation<ServerOperationResult> clearGlobal(
            ServerSoundInstance instance, Collection<ServerPlayer> audience) {
        return AuralisServerManager.clearGlobal(instance, audience);
    }

    public static Optional<ServerSoundInstance> find(MinecraftServer server, String id) {
        return AuralisServerManager.find(server, id);
    }
}
