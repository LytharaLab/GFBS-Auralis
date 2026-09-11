package org.lytharalab.gfbs.auralis.api;

import org.lytharalab.gfbs.auralis.network.ClientSoundRequestPacket;

import java.util.Objects;

/** Client request handle. Every mutation still requires server validation and broadcast. */
public final class ClientSoundInstance {
    private final String id;

    ClientSoundInstance(String id) { this.id = Objects.requireNonNull(id, "id"); }
    public String id() { return id; }
    public AuralisOperation<ClientRequestResult> play() { return request(ClientSoundRequestPacket.Action.PLAY, null, 0.0, AuralisOperation.Kind.PLAY); }
    public AuralisOperation<ClientRequestResult> pause() { return request(ClientSoundRequestPacket.Action.PAUSE, null, 0.0, AuralisOperation.Kind.PAUSE); }
    public AuralisOperation<ClientRequestResult> stop() { return request(ClientSoundRequestPacket.Action.STOP, null, 0.0, AuralisOperation.Kind.STOP); }
    public AuralisOperation<ClientRequestResult> seek(double seconds) { return request(ClientSoundRequestPacket.Action.SEEK, null, seconds, AuralisOperation.Kind.SEEK); }
    public AuralisOperation<ClientRequestResult> update(AuralisSoundSpec spec) { return request(ClientSoundRequestPacket.Action.UPDATE, spec, 0.0, AuralisOperation.Kind.UPDATE); }
    public AuralisOperation<ClientRequestResult> dispose() { return request(ClientSoundRequestPacket.Action.DISPOSE, null, 0.0, AuralisOperation.Kind.DISPOSE); }

    private AuralisOperation<ClientRequestResult> request(ClientSoundRequestPacket.Action action,
                                                           AuralisSoundSpec spec, double seconds,
                                                           AuralisOperation.Kind kind) {
        return AuralisClientApi.request(kind, new ClientSoundRequestPacket(java.util.UUID.randomUUID(), action, id, spec, seconds));
    }
}
