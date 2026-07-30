package org.lytharalab.gfbs.auralis.server;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.lytharalab.gfbs.auralis.GFBsAuralisConfig;
import org.lytharalab.gfbs.auralis.network.LoopSoundPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;
import org.lytharalab.gfbs.auralis.network.PlaySoundPacket;
import org.lytharalab.gfbs.auralis.network.StopSoundPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuralisServerManager {
    private static final Map<UUID, Map<ResourceLocation, SoundState>> playerSounds = new ConcurrentHashMap<>();

    private static int maxSoundsPerPlayer() {
        return GFBsAuralisConfig.SERVER.maxConcurrentSounds.get();
    }

    private static boolean canUpsertSound(Map<ResourceLocation, SoundState> sounds, ResourceLocation soundId) {
        return sounds.containsKey(soundId) || sounds.size() < maxSoundsPerPlayer();
    }

    public static void playSound(ServerPlayer player, ResourceLocation soundId, float volume, float pitch, boolean looping) {
        UUID playerId = player.getUUID();
        Map<ResourceLocation, SoundState> sounds = playerSounds.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        if (!canUpsertSound(sounds, soundId)) {
            return;
        }

        float v = Mth.clamp(volume, 0.0f, 1.0f);
        float p = Mth.clamp(pitch, 0.01f, 8.0f);
        sounds.put(soundId, new SoundState(v, p, looping));

        NetworkHandler.CHANNEL.sendTo(new PlaySoundPacket(soundId, v, p, looping),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void stopSound(ServerPlayer player, ResourceLocation soundId) {
        UUID playerId = player.getUUID();
        if (playerSounds.containsKey(playerId)) {
            playerSounds.get(playerId).remove(soundId);

            NetworkHandler.CHANNEL.sendTo(new StopSoundPacket(soundId),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    public static void setLooping(ServerPlayer player, ResourceLocation soundId, boolean looping) {
        UUID playerId = player.getUUID();
        if (playerSounds.containsKey(playerId) && playerSounds.get(playerId).containsKey(soundId)) {
            SoundState state = playerSounds.get(playerId).get(soundId);
            state.setLooping(looping);

            NetworkHandler.CHANNEL.sendTo(new LoopSoundPacket(soundId, looping),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    public static void syncSound(ServerPlayer player, ResourceLocation soundId, float volume, float pitch, boolean looping) {
        UUID playerId = player.getUUID();
        Map<ResourceLocation, SoundState> sounds = playerSounds.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        if (!canUpsertSound(sounds, soundId)) {
            return;
        }
        float v = Mth.clamp(volume, 0.0f, 1.0f);
        float p = Mth.clamp(pitch, 0.01f, 8.0f);
        sounds.put(soundId, new SoundState(v, p, looping));
    }

    public static void syncAllSoundsToPlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (playerSounds.containsKey(playerId)) {
            Map<ResourceLocation, SoundState> sounds = playerSounds.get(playerId);
            for (Map.Entry<ResourceLocation, SoundState> entry : sounds.entrySet()) {
                ResourceLocation soundId = entry.getKey();
                SoundState state = entry.getValue();

                NetworkHandler.CHANNEL.sendTo(new PlaySoundPacket(soundId, state.getVolume(), state.getPitch(), state.isLooping()),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    public static void onPlayerLogin(ServerPlayer player) {
        syncAllSoundsToPlayer(player);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        playerSounds.remove(player.getUUID());
    }

    public static void onServerStop() {
        playerSounds.clear();
    }

    private static class SoundState {
        private float volume;
        private float pitch;
        private boolean looping;

        public SoundState(float volume, float pitch, boolean looping) {
            this.volume = volume;
            this.pitch = pitch;
            this.looping = looping;
        }

        public float getVolume() {
            return volume;
        }

        public float getPitch() {
            return pitch;
        }

        public boolean isLooping() {
            return looping;
        }

        public void setLooping(boolean looping) {
            this.looping = looping;
        }
    }
}
