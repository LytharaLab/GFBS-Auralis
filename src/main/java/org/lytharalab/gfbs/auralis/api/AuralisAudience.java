package org.lytharalab.gfbs.auralis.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent replication scope for a server-authoritative Auralis instance.
 *
 * <p>Unlike a one-shot collection of currently connected players, {@link #all()}
 * and {@link #dimension(ResourceKey)} also match players who connect later. A
 * fixed player audience deliberately retains the old targeted-control semantics.</p>
 */
public sealed interface AuralisAudience
        permits AuralisAudience.All, AuralisAudience.Dimension, AuralisAudience.Players {
    AuralisAudience ALL = new All();

    boolean includes(ServerPlayer player);

    default List<ServerPlayer> resolve(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.getPlayerList().getPlayers().stream()
                .filter(this::includes)
                .toList();
    }

    static AuralisAudience all() {
        return ALL;
    }

    static AuralisAudience dimension(ResourceKey<Level> dimension) {
        return new Dimension(dimension);
    }

    static AuralisAudience player(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return new Players(Set.of(player.getUUID()));
    }

    static AuralisAudience players(Collection<ServerPlayer> players) {
        Objects.requireNonNull(players, "players");
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ServerPlayer player : players) {
            if (player != null) ids.add(player.getUUID());
        }
        return new Players(ids);
    }

    /** All players on the running Minecraft server, including future joins. */
    record All() implements AuralisAudience {
        @Override
        public boolean includes(ServerPlayer player) {
            return player != null;
        }
    }

    /** Players currently or subsequently present in one dimension. */
    record Dimension(ResourceKey<Level> dimension) implements AuralisAudience {
        public Dimension {
            Objects.requireNonNull(dimension, "dimension");
        }

        @Override
        public boolean includes(ServerPlayer player) {
            return player != null && player.level().dimension().equals(dimension);
        }
    }

    /** A fixed UUID set; players outside it never receive the instance. */
    record Players(Set<UUID> playerIds) implements AuralisAudience {
        public Players {
            playerIds = Set.copyOf(Objects.requireNonNull(playerIds, "playerIds"));
        }

        @Override
        public boolean includes(ServerPlayer player) {
            return player != null && playerIds.contains(player.getUUID());
        }
    }
}
