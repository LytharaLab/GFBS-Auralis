package org.lytharalab.gfbs.auralis.api;

import net.minecraft.server.MinecraftServer;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;

import java.util.Objects;
import java.util.Optional;

/** Opaque handle to state owned by one Minecraft server. */
public final class ServerSoundInstance {
    private final MinecraftServer server;
    private final String id;
    private final long epoch;

    public ServerSoundInstance(MinecraftServer server, String id, long epoch) {
        this.server = Objects.requireNonNull(server, "server");
        this.id = Objects.requireNonNull(id, "id");
        this.epoch = epoch;
    }

    public MinecraftServer server() { return server; }
    public String id() { return id; }
    public long epoch() { return epoch; }
    public Optional<AuthoritativeSoundSnapshot> snapshot() { return AuralisServerManager.snapshot(this); }
    public AuralisOperation<ServerOperationResult> play() { return AuralisServerManager.play(this); }
    public AuralisOperation<ServerOperationResult> pause() { return AuralisServerManager.pause(this); }
    public AuralisOperation<ServerOperationResult> stop() { return AuralisServerManager.stop(this); }
    public AuralisOperation<ServerOperationResult> seek(double seconds) { return AuralisServerManager.seek(this, seconds); }
    public AuralisOperation<ServerOperationResult> update(AuralisSoundSpec spec) { return AuralisServerManager.update(this, spec); }
    public AuralisOperation<ServerOperationResult> dispose() { return AuralisServerManager.dispose(this); }
}
