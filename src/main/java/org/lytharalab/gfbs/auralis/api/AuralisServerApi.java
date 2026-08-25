package org.lytharalab.gfbs.auralis.api;
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
 * is furnished to do so, subject to the following conditions:
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

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.network.BusControlPacket;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative creation, control, targeting, and late-join replication API. */
public class AuralisServerApi {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public AuralisServerApi() {
    }

    /* --------------------------------------------------------------------- */
    /* Creation                                                               */
    /* --------------------------------------------------------------------- */

    /** Backwards-compatible fixed-player playback. */
    public static int playSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            Collection<ServerPlayer> targets
    ) {
        return playSound(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, 0.0, audience(targets)
        );
    }

    /** Persistent audience playback; use {@link AuralisAudience#all()} for future joins. */
    public static int playSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            AuralisAudience audience
    ) {
        return playSound(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, 0.0, audience
        );
    }

    /**
     * Persistent audience playback with an optional server-known media duration.
     * A positive duration enables exact authoritative one-shot expiry; zero keeps
     * the instance until an explicit stop while clients still suppress audio past
     * their locally decoded duration.
     */
    public static int playSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            double durationSeconds,
            AuralisAudience audience
    ) {
        return playInternal(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, false, durationSeconds, audience
        );
    }

    /** Backwards-compatible fixed-player streamed playback. */
    public static int playStreamedSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            Collection<ServerPlayer> targets
    ) {
        return playStreamedSound(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, 0.0, audience(targets)
        );
    }

    public static int playStreamedSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            AuralisAudience audience
    ) {
        return playStreamedSound(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, 0.0, audience
        );
    }

    public static int playStreamedSound(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            double durationSeconds,
            AuralisAudience audience
    ) {
        return playInternal(
                id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                priority, minDistance, maxDistance, true, durationSeconds, audience
        );
    }

    private static int playInternal(
            String id,
            ResourceLocation soundEventId,
            float volume,
            float pitch,
            float speed,
            boolean isStatic,
            Vec3 pos,
            boolean looping,
            int priority,
            float minDistance,
            float maxDistance,
            boolean streamed,
            double durationSeconds,
            AuralisAudience audience
    ) {
        return AuralisServerManager.playSound(
                Objects.requireNonNull(audience, "audience"),
                id,
                soundEventId,
                volume,
                pitch,
                speed,
                isStatic,
                pos,
                looping,
                priority,
                minDistance,
                maxDistance,
                streamed,
                durationSeconds
        );
    }

    /* --------------------------------------------------------------------- */
    /* Playback and properties                                                */
    /* --------------------------------------------------------------------- */

    public static int pauseSound(String id, Collection<ServerPlayer> targets) {
        return pauseSound(id, audience(targets));
    }

    public static int pauseSound(String id, AuralisAudience audience) {
        return AuralisServerManager.pauseSound(audience, id);
    }

    public static int resumeSound(String id, Collection<ServerPlayer> targets) {
        return resumeSound(id, audience(targets));
    }

    public static int resumeSound(String id, AuralisAudience audience) {
        return AuralisServerManager.resumeSound(audience, id);
    }

    public static int stopSound(String id, Collection<ServerPlayer> targets) {
        return stopSound(id, audience(targets));
    }

    public static int stopSound(String id, AuralisAudience audience) {
        return AuralisServerManager.stopSound(audience, id);
    }

    public static int setVolume(String id, float value, Collection<ServerPlayer> targets) {
        return setVolume(id, value, audience(targets));
    }

    public static int setVolume(String id, float value, AuralisAudience audience) {
        return AuralisServerManager.setVolume(audience, id, value);
    }

    public static int setPitch(String id, float value, Collection<ServerPlayer> targets) {
        return setPitch(id, value, audience(targets));
    }

    public static int setPitch(String id, float value, AuralisAudience audience) {
        return AuralisServerManager.setPitch(audience, id, value);
    }

    public static int setSpeed(String id, float value, Collection<ServerPlayer> targets) {
        return setSpeed(id, value, audience(targets));
    }

    public static int setSpeed(String id, float value, AuralisAudience audience) {
        return AuralisServerManager.setSpeed(audience, id, value);
    }

    public static int setPosition(String id, Vec3 value, Collection<ServerPlayer> targets) {
        return setPosition(id, value, audience(targets));
    }

    public static int setPosition(String id, Vec3 value, AuralisAudience audience) {
        return AuralisServerManager.setPosition(audience, id, value);
    }

    public static int setStatic(String id, boolean value, Collection<ServerPlayer> targets) {
        return setStatic(id, value, audience(targets));
    }

    public static int setStatic(String id, boolean value, AuralisAudience audience) {
        return AuralisServerManager.setStatic(audience, id, value);
    }

    public static int setLooping(String id, boolean value, Collection<ServerPlayer> targets) {
        return setLooping(id, value, audience(targets));
    }

    public static int setLooping(String id, boolean value, AuralisAudience audience) {
        return AuralisServerManager.setLooping(audience, id, value);
    }

    public static int setPriority(String id, int value, Collection<ServerPlayer> targets) {
        return setPriority(id, value, audience(targets));
    }

    public static int setPriority(String id, int value, AuralisAudience audience) {
        return AuralisServerManager.setPriority(audience, id, value);
    }

    public static int setMinDistance(String id, float value, Collection<ServerPlayer> targets) {
        return setMinDistance(id, value, audience(targets));
    }

    public static int setMinDistance(String id, float value, AuralisAudience audience) {
        return AuralisServerManager.setMinDistance(audience, id, value);
    }

    public static int setMaxDistance(String id, float value, Collection<ServerPlayer> targets) {
        return setMaxDistance(id, value, audience(targets));
    }

    public static int setMaxDistance(String id, float value, AuralisAudience audience) {
        return AuralisServerManager.setMaxDistance(audience, id, value);
    }

    /* --------------------------------------------------------------------- */
    /* Server-clock tweens and bindings                                       */
    /* --------------------------------------------------------------------- */

    public static int tween(
            String id,
            TweenControlPacket.Property property,
            double value,
            float duration,
            Collection<ServerPlayer> targets
    ) {
        return tween(id, property, value, duration, EasingStyle.LINEAR, EasingDirection.OUT, audience(targets));
    }

    public static int tween(
            String id,
            TweenControlPacket.Property property,
            double value,
            float duration,
            EasingStyle style,
            EasingDirection direction,
            Collection<ServerPlayer> targets
    ) {
        return tween(id, property, value, duration, style, direction, audience(targets));
    }

    public static int tween(
            String id,
            TweenControlPacket.Property property,
            double value,
            float duration,
            EasingStyle style,
            EasingDirection direction,
            AuralisAudience audience
    ) {
        return AuralisServerManager.tween(
                audience, id, property, value, 0.0, 0.0, duration, style, direction
        );
    }

    public static int tweenPosition(
            String id,
            Vec3 target,
            float duration,
            Collection<ServerPlayer> targets
    ) {
        return tweenPosition(
                id, target, duration, EasingStyle.LINEAR, EasingDirection.OUT, audience(targets)
        );
    }

    public static int tweenPosition(
            String id,
            Vec3 target,
            float duration,
            EasingStyle style,
            EasingDirection direction,
            Collection<ServerPlayer> targets
    ) {
        return tweenPosition(id, target, duration, style, direction, audience(targets));
    }

    public static int tweenPosition(
            String id,
            Vec3 target,
            float duration,
            EasingStyle style,
            EasingDirection direction,
            AuralisAudience audience
    ) {
        return AuralisServerManager.tween(
                audience, id, TweenControlPacket.Property.POSITION,
                target.x, target.y, target.z, duration, style, direction
        );
    }

    public static int bindEntity(String id, int entityId, Collection<ServerPlayer> targets) {
        return bindEntity(id, entityId, ZERO_UUID, audience(targets));
    }

    public static int bindEntity(
            String id,
            int entityId,
            UUID entityUuid,
            AuralisAudience audience
    ) {
        return AuralisServerManager.bindEntity(audience, id, entityId, entityUuid);
    }

    public static int bindBlock(String id, BlockPos pos, Collection<ServerPlayer> targets) {
        return bindBlock(id, pos, audience(targets));
    }

    public static int bindBlock(String id, BlockPos pos, AuralisAudience audience) {
        return AuralisServerManager.bindBlock(audience, id, pos);
    }

    public static int unbind(String id, Collection<ServerPlayer> targets) {
        return unbind(id, audience(targets));
    }

    public static int unbind(String id, AuralisAudience audience) {
        return AuralisServerManager.unbind(audience, id);
    }

    /* --------------------------------------------------------------------- */
    /* Bus state                                                              */
    /* --------------------------------------------------------------------- */

    public static int setBus(String soundId, String busName, Collection<ServerPlayer> targets) {
        return setBus(soundId, busName, audience(targets));
    }

    public static int setBus(String soundId, String busName, AuralisAudience audience) {
        return AuralisServerManager.setBus(audience, soundId, busName);
    }

    public static int createBus(String name, String parent, Collection<ServerPlayer> targets) {
        return createBus(name, parent, audience(targets));
    }

    public static int createBus(String name, String parent, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.CREATE_BUS, name, parent, 0f, false), audience);
    }

    public static int removeBus(String name, Collection<ServerPlayer> targets) {
        return removeBus(name, audience(targets));
    }

    public static int removeBus(String name, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.REMOVE_BUS, name, "Master", 0f, false), audience);
    }

    public static int setBusParent(String name, String parent, Collection<ServerPlayer> targets) {
        return setBusParent(name, parent, audience(targets));
    }

    public static int setBusParent(String name, String parent, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.SET_PARENT, name, parent, 0f, false), audience);
    }

    public static int setBusVolume(String name, float value, Collection<ServerPlayer> targets) {
        return setBusVolume(name, value, audience(targets));
    }

    public static int setBusVolume(String name, float value, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.SET_VOLUME, name, "Master", value, false), audience);
    }

    public static int setBusMuted(String name, boolean value, Collection<ServerPlayer> targets) {
        return setBusMuted(name, value, audience(targets));
    }

    public static int setBusMuted(String name, boolean value, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.SET_MUTED, name, "Master", 0f, value), audience);
    }

    public static int setBusSolo(String name, boolean value, Collection<ServerPlayer> targets) {
        return setBusSolo(name, value, audience(targets));
    }

    public static int setBusSolo(String name, boolean value, AuralisAudience audience) {
        return bus(new BusControlPacket(BusControlPacket.Action.SET_SOLO, name, "Master", 0f, value), audience);
    }

    public static int setBusEffectsBypassed(String name, boolean value, Collection<ServerPlayer> targets) {
        return setBusEffectsBypassed(name, value, audience(targets));
    }

    public static int setBusEffectsBypassed(String name, boolean value, AuralisAudience audience) {
        return bus(new BusControlPacket(
                BusControlPacket.Action.SET_EFFECTS_BYPASSED, name, "Master", 0f, value
        ), audience);
    }

    private static int bus(BusControlPacket packet, AuralisAudience audience) {
        return AuralisServerManager.applyBusControl(audience, packet);
    }

    private static AuralisAudience audience(Collection<ServerPlayer> targets) {
        if (targets == null) return new AuralisAudience.Players(java.util.Set.of());
        return AuralisAudience.players(targets);
    }
}
