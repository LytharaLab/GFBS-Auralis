package org.mirage.gfbs.auralis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.mirage.gfbs.auralis.network.NetworkHandler;
import org.mirage.gfbs.auralis.network.SoundControlPacket;
import org.mirage.gfbs.auralis.network.TweenControlPacket;
import org.mirage.gfbs.auralis.tween.EasingDirection;
import org.mirage.gfbs.auralis.tween.EasingStyle;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public final class SoundCommand {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_EASING_STYLES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(EasingStyle.values()).map(Enum::name).map(String::toLowerCase),
                    builder
            );

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_EASING_DIRECTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(EasingDirection.values()).map(Enum::name).map(String::toLowerCase),
                    builder
            );

    private SoundCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        ArgumentBuilder<CommandSourceStack, ?> tweenVolume = Commands.literal("volume")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 2.0f))
                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.VOLUME, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.VOLUME, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.VOLUME, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.VOLUME, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> tweenPitch = Commands.literal("pitch")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.5f, 2.0f))
                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.PITCH, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.PITCH, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.PITCH, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.PITCH, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> tweenSpeed = Commands.literal("speed")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 5.0f))
                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.SPEED, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.SPEED, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.SPEED, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.SPEED, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> tweenPosition = Commands.literal("position")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", Vec3Argument.vec3())
                                        .executes(ctx -> tweenPosition(ctx, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tweenPosition(ctx, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tweenPosition(ctx, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tweenPosition(ctx, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> tweenMinDistance = Commands.literal("min-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.MIN_DISTANCE, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.MIN_DISTANCE, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.MIN_DISTANCE, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.MIN_DISTANCE, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> tweenMaxDistance = Commands.literal("max-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.05f, 3600.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.MAX_DISTANCE, null))
                                        .then(Commands.argument("easing-style", StringArgumentType.word())
                                                .suggests(SUGGEST_EASING_STYLES)
                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.MAX_DISTANCE, null))
                                                .then(Commands.argument("easing-direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_EASING_DIRECTIONS)
                                                        .executes(ctx -> tween(ctx, TweenControlPacket.Property.MAX_DISTANCE, null))
                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                .executes(ctx -> tween(ctx, TweenControlPacket.Property.MAX_DISTANCE, EntityArgument.getPlayers(ctx, "targets")))
                                                        )
                                                )
                                        )
                                )
                        )
                );

        dispatcher.register(Commands.literal("gfbs_auralis")
                        .requires(source -> source.hasPermission(2))

                        // /auralis play <sound> <id> <volume> <pitch> <speed> <static> <position> <looping> <priority> <min-distance> <max-distance> [targets]
                        .then(Commands.literal("play")
                                .then(Commands.argument("sound", ResourceArgument.resource(buildContext, Registries.SOUND_EVENT))
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f, 2.0f))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.5f, 2.0f))
                                                                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.1f, 5.0f))
                                                                        .then(Commands.argument("static", BoolArgumentType.bool())
                                                                                .then(Commands.argument("position", Vec3Argument.vec3())
                                                                                        .then(Commands.argument("looping", BoolArgumentType.bool())
                                                                                                .then(Commands.argument("priority", IntegerArgumentType.integer(0, 100))
                                                                                                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                                                                                                .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                                                                                                        .executes(ctx -> playSound(ctx, null, false))
                                                                                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                                                                                .executes(ctx -> playSound(ctx, EntityArgument.getPlayers(ctx, "targets"), false)))))))))))))))

                        // /auralis streamed_play <sound> <id> <volume> <pitch> <speed> <static> <position> <looping> <priority> <min-distance> <max-distance> [targets]
                        .then(Commands.literal("streamed_play")
                                .then(Commands.argument("sound", ResourceArgument.resource(buildContext, Registries.SOUND_EVENT))
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f, 2.0f))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.5f, 2.0f))
                                                                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.1f, 5.0f))
                                                                        .then(Commands.argument("static", BoolArgumentType.bool())
                                                                                .then(Commands.argument("position", Vec3Argument.vec3())
                                                                                        .then(Commands.argument("looping", BoolArgumentType.bool())
                                                                                                .then(Commands.argument("priority", IntegerArgumentType.integer(0, 100))
                                                                                                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                                                                                                .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                                                                                                        .executes(ctx -> playSound(ctx, null, true))
                                                                                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                                                                                .executes(ctx -> playSound(ctx, EntityArgument.getPlayers(ctx, "targets"), true)))))))))))))))

                // /auralis pause <id> [targets]
                .then(Commands.literal("pause")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> pauseSound(ctx, StringArgumentType.getString(ctx, "id"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> pauseSound(ctx, StringArgumentType.getString(ctx, "id"), EntityArgument.getPlayers(ctx, "targets"))))))

                // /auralis stop <id> [targets]
                .then(Commands.literal("stop")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> stopSound(ctx, StringArgumentType.getString(ctx, "id"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> stopSound(ctx, StringArgumentType.getString(ctx, "id"), EntityArgument.getPlayers(ctx, "targets"))))))

                // /auralis regulating <prop> <id> <value...> [targets]
                .then(Commands.literal("regulating")
                        .then(Commands.literal("volume")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f, 2.0f))
                                                .executes(ctx -> setVolume(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "volume"), null))
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(ctx -> setVolume(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "volume"), EntityArgument.getPlayers(ctx, "targets")))))))
                .then(Commands.literal("pitch")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.5f, 2.0f))
                                        .executes(ctx -> setPitch(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "pitch"), null))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> setPitch(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "pitch"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("speed")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.1f, 5.0f))
                                .executes(ctx -> setSpeed(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "speed"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setSpeed(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "speed"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("position")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("position", Vec3Argument.vec3())
                                .executes(ctx -> setPosition(ctx, StringArgumentType.getString(ctx, "id"), Vec3Argument.getVec3(ctx, "position"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setPosition(ctx, StringArgumentType.getString(ctx, "id"), Vec3Argument.getVec3(ctx, "position"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("static")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("static", BoolArgumentType.bool())
                                .executes(ctx -> setStatic(ctx, StringArgumentType.getString(ctx, "id"), BoolArgumentType.getBool(ctx, "static"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setStatic(ctx, StringArgumentType.getString(ctx, "id"), BoolArgumentType.getBool(ctx, "static"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("looping")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("looping", BoolArgumentType.bool())
                                .executes(ctx -> setLooping(ctx, StringArgumentType.getString(ctx, "id"), BoolArgumentType.getBool(ctx, "looping"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setLooping(ctx, StringArgumentType.getString(ctx, "id"), BoolArgumentType.getBool(ctx, "looping"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("priority")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("priority", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> setPriority(ctx, StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "priority"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setPriority(ctx, StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "priority"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("min-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                .executes(ctx -> setMinDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "min-distance"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setMinDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "min-distance"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("max-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.1f, 1000.0f))
                                .executes(ctx -> setMaxDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "max-distance"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setMaxDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "max-distance"), EntityArgument.getPlayers(ctx, "targets"))))))))

                // /auralis tween <prop> <id> <duration> <value> [easing-style] [easing-direction] [targets]
                .then(Commands.literal("tween")
                        .then(tweenVolume)
                        .then(tweenPitch)
                        .then(tweenSpeed)
                        .then(tweenPosition)
                        .then(tweenMinDistance)
                        .then(tweenMaxDistance)
                )
        );
    }

    private static int playSound(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets, boolean isStreamed) throws CommandSyntaxException {
        Holder.Reference<?> holder = ResourceArgument.getResource(ctx, "sound", Registries.SOUND_EVENT);
        ResourceLocation soundEventId = holder.key().location();

        String id = StringArgumentType.getString(ctx, "id");
        float volume = FloatArgumentType.getFloat(ctx, "volume");
        float pitch = FloatArgumentType.getFloat(ctx, "pitch");
        float speed = FloatArgumentType.getFloat(ctx, "speed");
        boolean isStatic = BoolArgumentType.getBool(ctx, "static");
        Vec3 pos = Vec3Argument.getVec3(ctx, "position");
        boolean looping = BoolArgumentType.getBool(ctx, "looping");
        int priority = IntegerArgumentType.getInteger(ctx, "priority");
        float minDistance = FloatArgumentType.getFloat(ctx, "min-distance");
        float maxDistance = FloatArgumentType.getFloat(ctx, "max-distance");

        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                isStreamed ? SoundControlPacket.Action.STREAMED_PLAY : SoundControlPacket.Action.PLAY,
                id,
                soundEventId,
                volume,
                pitch,
                speed,
                isStatic,
                pos.x, pos.y, pos.z,
                looping,
                priority,
                minDistance,
                maxDistance
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;
        String action = isStreamed ? "流式播放" : "播放";
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送" + action + "指令: " + soundEventId + " (id=" + id + ")"),
                false
        );
        return 1;
    }

    private static int pauseSound(CommandContext<CommandSourceStack> ctx, String id, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.PAUSE,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送暂停指令 (id=" + id + ")"),
                false
        );
        return 1;
    }

    private static int stopSound(CommandContext<CommandSourceStack> ctx, String id, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.STOP,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送停止指令 (id=" + id + ")"),
                false
        );
        return 1;
    }

    private static int setVolume(CommandContext<CommandSourceStack> ctx, String id, float volume, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_VOLUME,
                id,
                new ResourceLocation("minecraft", "empty"),
                volume, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置音量 (id=" + id + ", volume=" + volume + ")"), false);
        return 1;
    }

    private static int setPitch(CommandContext<CommandSourceStack> ctx, String id, float pitch, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_PITCH,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, pitch, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置音高 (id=" + id + ", pitch=" + pitch + ")"), false);
        return 1;
    }

    private static int setSpeed(CommandContext<CommandSourceStack> ctx, String id, float speed, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_SPEED,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, speed,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置速度 (id=" + id + ", speed=" + speed + ")"), false);
        return 1;
    }

    private static int setPosition(CommandContext<CommandSourceStack> ctx, String id, Vec3 pos, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_POSITION,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                pos.x, pos.y, pos.z,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置位置 (id=" + id + ", pos=" + pos.x + "," + pos.y + "," + pos.z + ")"), false);
        return 1;
    }

    private static int setStatic(CommandContext<CommandSourceStack> ctx, String id, boolean isStatic, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_STATIC,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                isStatic,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置静态模式 (id=" + id + ", static=" + isStatic + ")"), false);
        return 1;
    }

    private static int setLooping(CommandContext<CommandSourceStack> ctx, String id, boolean looping, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_LOOPING,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                looping,
                0,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置循环 (id=" + id + ", looping=" + looping + ")"), false);
        return 1;
    }

    private static int setPriority(CommandContext<CommandSourceStack> ctx, String id, int priority, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_PRIORITY,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                priority,
                0.1f,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置优先级 (id=" + id + ", priority=" + priority + ")"), false);
        return 1;
    }

    private static int setMinDistance(CommandContext<CommandSourceStack> ctx, String id, float minDistance, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_MIN_DISTANCE,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                minDistance,
                0.1f
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置最小距离 (id=" + id + ", minDistance=" + minDistance + ")"), false);
        return 1;
    }

    private static int setMaxDistance(CommandContext<CommandSourceStack> ctx, String id, float maxDistance, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        SoundControlPacket packet = new SoundControlPacket(
                SoundControlPacket.Action.SET_MAX_DISTANCE,
                id,
                new ResourceLocation("minecraft", "empty"),
                0f, 0f, 0f,
                false,
                0d, 0d, 0d,
                false,
                0,
                0.1f,
                maxDistance
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置最大距离 (id=" + id + ", maxDistance=" + maxDistance + ")"), false);
        return 1;
    }

    private static int tween(CommandContext<CommandSourceStack> ctx, TweenControlPacket.Property property, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        String id = StringArgumentType.getString(ctx, "id");
        float duration = FloatArgumentType.getFloat(ctx, "duration");
        float value = FloatArgumentType.getFloat(ctx, "value");
        String easingStyleStr = tryGetString(ctx, "easing-style");
        String easingDirectionStr = tryGetString(ctx, "easing-direction");
        EasingStyle easingStyle = parseEasingStyle(easingStyleStr);
        EasingDirection easingDirection = parseEasingDirection(easingDirectionStr);

        TweenControlPacket packet = new TweenControlPacket(
                property, id,
                value, 0d, 0d,
                duration,
                easingStyle, easingDirection
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送渐变指令 (id=" + id + ", prop=" + property.name() + ", target=" + value + ", duration=" + duration + "s)"),
                false
        );
        return 1;
    }

    private static int tweenPosition(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        String id = StringArgumentType.getString(ctx, "id");
        float duration = FloatArgumentType.getFloat(ctx, "duration");
        Vec3 targetPos = Vec3Argument.getVec3(ctx, "value");
        String easingStyleStr = tryGetString(ctx, "easing-style");
        String easingDirectionStr = tryGetString(ctx, "easing-direction");
        EasingStyle easingStyle = parseEasingStyle(easingStyleStr);
        EasingDirection easingDirection = parseEasingDirection(easingDirectionStr);

        TweenControlPacket packet = new TweenControlPacket(
                TweenControlPacket.Property.POSITION, id,
                targetPos.x, targetPos.y, targetPos.z,
                duration,
                easingStyle, easingDirection
        );

        int sent = 0;
        for (ServerPlayer p : targets) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
            sent++;
        }

        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送位置渐变指令 (id=" + id + ", target=" + targetPos.x + "," + targetPos.y + "," + targetPos.z + ", duration=" + duration + "s)"),
                false
        );
        return 1;
    }

    private static Collection<ServerPlayer> resolveTargets(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets) {
        if (explicitTargets != null) {
            return explicitTargets;
        }

        try {
            ServerPlayer self = ctx.getSource().getPlayerOrException();
            return Collections.singletonList(self);
        } catch (Exception ignored) {
            ctx.getSource().sendFailure(Component.literal("[GFBS Auralis] 该命令来源不是玩家（如命令方块/控制台），必须指定 targets 参数（例如 @p/@a/玩家名）。"));
            return null;
        }
    }

    private static String tryGetString(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EasingStyle parseEasingStyle(String name) {
        if (name == null) return EasingStyle.LINEAR;
        try {
            return EasingStyle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EasingStyle.LINEAR;
        }
    }

    private static EasingDirection parseEasingDirection(String name) {
        if (name == null) return EasingDirection.OUT;
        try {
            return EasingDirection.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EasingDirection.OUT;
        }
    }

    private static int unsupportedOnServer(CommandContext<CommandSourceStack> ctx, String sub) {
        ctx.getSource().sendFailure(Component.literal("[GFBS Auralis] /auralis " + sub + " 无法在服务器侧直接查询客户端音源状态（此版本已改为通过网络在客户端播放/控制）。"));
        ctx.getSource().sendFailure(Component.literal("[GFBS Auralis] 你可以在客户端日志里查看播放/报错信息。"));
        return 0;
    }
}
