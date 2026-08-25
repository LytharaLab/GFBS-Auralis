package org.lytharalab.gfbs.auralis.network.sync;

import net.minecraft.network.FriendlyByteBuf;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;

import java.util.Objects;

/** Compact final-state representation of a remotely managed audio bus. */
public record SyncedBusState(
        long revision,
        String name,
        String parent,
        float volume,
        boolean muted,
        boolean solo,
        boolean effectsBypassed,
        boolean removed
) {
    public static final int MAX_NAME_LENGTH = 96;

    public SyncedBusState {
        revision = Math.max(0L, revision);
        name = bounded(Objects.requireNonNull(name, "name"));
        parent = bounded(Objects.requireNonNullElse(parent, AudioBusSystem.MASTER));
        volume = Float.isFinite(volume) ? Math.max(0.0f, Math.min(16.0f, volume)) : 1.0f;
        if (AudioBusSystem.MASTER.equals(name)) removed = false;
    }

    public static SyncedBusState create(long revision, String name, String parent) {
        return new SyncedBusState(revision, name, parent, 1.0f, false, false, false, false);
    }

    public SyncedBusState withRevision(long nextRevision) {
        return new SyncedBusState(
                nextRevision, name, parent, volume, muted, solo, effectsBypassed, removed
        );
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(revision);
        buffer.writeUtf(name, MAX_NAME_LENGTH);
        buffer.writeUtf(parent, MAX_NAME_LENGTH);
        buffer.writeFloat(volume);
        buffer.writeBoolean(muted);
        buffer.writeBoolean(solo);
        buffer.writeBoolean(effectsBypassed);
        buffer.writeBoolean(removed);
    }

    public static SyncedBusState decode(FriendlyByteBuf buffer) {
        return new SyncedBusState(
                buffer.readVarLong(),
                buffer.readUtf(MAX_NAME_LENGTH),
                buffer.readUtf(MAX_NAME_LENGTH),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean()
        );
    }

    private static String bounded(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid Auralis bus name length: " + trimmed.length());
        }
        return trimmed;
    }
}
