package org.lytharalab.gfbs.auralis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.AuralisAudience;
import org.lytharalab.gfbs.auralis.api.AuralisServerApi;
import org.lytharalab.gfbs.auralis.network.BusControlPacket;
import org.lytharalab.gfbs.auralis.network.TweenControlPacket;
import org.lytharalab.gfbs.auralis.server.AuralisServerManager;
import org.lytharalab.gfbs.auralis.tween.EasingDirection;
import org.lytharalab.gfbs.auralis.tween.EasingStyle;

import java.util.Collection;
import java.util.Collections;

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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("duration", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
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

        ArgumentBuilder<CommandSourceStack, ?> bindEntity = Commands.literal("entity")
                .then(Commands.argument("target_entity", EntityArgument.entity())
                        .executes(ctx -> bindEntityCmd(ctx, null))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> bindEntityCmd(ctx, EntityArgument.getPlayers(ctx, "targets")))
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> bindBlock = Commands.literal("block")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> bindBlockCmd(ctx, null))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> bindBlockCmd(ctx, EntityArgument.getPlayers(ctx, "targets")))
                        )
                );
        ArgumentBuilder<CommandSourceStack, ?> bindCmd = Commands.literal("bind")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(bindEntity)
                        .then(bindBlock)
                );
        ArgumentBuilder<CommandSourceStack, ?> unbindCmd = Commands.literal("unbind")
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> unbindCmd(ctx, null))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> unbindCmd(ctx, EntityArgument.getPlayers(ctx, "targets")))
                        )
                );

        ArgumentBuilder<CommandSourceStack, ?> busCmd = Commands.literal("bus");

        ArgumentBuilder<CommandSourceStack, ?> createParent = busTerminal(
                Commands.argument("parent", StringArgumentType.word()),
                BusControlPacket.Action.CREATE_BUS
        );
        ArgumentBuilder<CommandSourceStack, ?> createName = Commands.argument("bus", StringArgumentType.word());
        createName.then(createParent);
        ArgumentBuilder<CommandSourceStack, ?> createBus = Commands.literal("create");
        createBus.then(createName);
        busCmd.then(createBus);

        ArgumentBuilder<CommandSourceStack, ?> removeName = busTerminal(
                Commands.argument("bus", StringArgumentType.word()),
                BusControlPacket.Action.REMOVE_BUS
        );
        ArgumentBuilder<CommandSourceStack, ?> removeBus = Commands.literal("remove");
        removeBus.then(removeName);
        busCmd.then(removeBus);

        ArgumentBuilder<CommandSourceStack, ?> parentTarget = busTerminal(
                Commands.argument("parent", StringArgumentType.word()),
                BusControlPacket.Action.SET_PARENT
        );
        ArgumentBuilder<CommandSourceStack, ?> parentName = Commands.argument("bus", StringArgumentType.word());
        parentName.then(parentTarget);
        ArgumentBuilder<CommandSourceStack, ?> setParent = Commands.literal("parent");
        setParent.then(parentName);
        busCmd.then(setParent);

        ArgumentBuilder<CommandSourceStack, ?> volumeValue = busTerminal(
                Commands.argument("value", FloatArgumentType.floatArg(0.0f, 16.0f)),
                BusControlPacket.Action.SET_VOLUME
        );
        ArgumentBuilder<CommandSourceStack, ?> volumeName = Commands.argument("bus", StringArgumentType.word());
        volumeName.then(volumeValue);
        ArgumentBuilder<CommandSourceStack, ?> setBusVolume = Commands.literal("volume");
        setBusVolume.then(volumeName);
        busCmd.then(setBusVolume);

        busCmd.then(busFlagCommand("mute", BusControlPacket.Action.SET_MUTED));
        busCmd.then(busFlagCommand("solo", BusControlPacket.Action.SET_SOLO));
        busCmd.then(busFlagCommand("bypass-effects", BusControlPacket.Action.SET_EFFECTS_BYPASSED));

        dispatcher.register(Commands.literal("gfbs_auralis")
                        .requires(source -> source.hasPermission(2))

                        // /auralis play <sound> <id> <volume> <pitch> <speed> <static> <position> <looping> <priority> <min-distance> <max-distance> [targets]
                        .then(Commands.literal("play")
                                .then(Commands.argument("sound", ResourceArgument.resource(buildContext, Registries.SOUND_EVENT))
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.0f))
                                                                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                                                                        .then(Commands.argument("static", BoolArgumentType.bool())
                                                                                .then(Commands.argument("position", Vec3Argument.vec3())
                                                                                        .then(Commands.argument("looping", BoolArgumentType.bool())
                                                                                                .then(Commands.argument("priority", IntegerArgumentType.integer(0))
                                                                                                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.0f))
                                                                                                                .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.0f))
                                                                                                                        .executes(ctx -> playSound(ctx, null, false))
                                                                                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                                                                                .executes(ctx -> playSound(ctx, EntityArgument.getPlayers(ctx, "targets"), false)))))))))))))))

                        // /auralis streamed_play <sound> <id> <volume> <pitch> <speed> <static> <position> <looping> <priority> <min-distance> <max-distance> [targets]
                        .then(Commands.literal("streamed_play")
                                .then(Commands.argument("sound", ResourceArgument.resource(buildContext, Registries.SOUND_EVENT))
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.0f))
                                                                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                                                                        .then(Commands.argument("static", BoolArgumentType.bool())
                                                                                .then(Commands.argument("position", Vec3Argument.vec3())
                                                                                        .then(Commands.argument("looping", BoolArgumentType.bool())
                                                                                                .then(Commands.argument("priority", IntegerArgumentType.integer(0))
                                                                                                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.0f))
                                                                                                                .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.0f))
                                                                                                                        .executes(ctx -> playSound(ctx, null, true))
                                                                                                                        .then(Commands.argument("targets", EntityArgument.players())
                                                                                                                                .executes(ctx -> playSound(ctx, EntityArgument.getPlayers(ctx, "targets"), true)))))))))))))))

                // /auralis pause <id> [targets]
                .then(Commands.literal("pause")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> pauseSound(ctx, StringArgumentType.getString(ctx, "id"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> pauseSound(ctx, StringArgumentType.getString(ctx, "id"), EntityArgument.getPlayers(ctx, "targets"))))))

                // /auralis resume <id> [targets]
                .then(Commands.literal("resume")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> resumeSound(ctx, StringArgumentType.getString(ctx, "id"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> resumeSound(ctx, StringArgumentType.getString(ctx, "id"), EntityArgument.getPlayers(ctx, "targets"))))))

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
                                        .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f))
                                                .executes(ctx -> setVolume(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "volume"), null))
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(ctx -> setVolume(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "volume"), EntityArgument.getPlayers(ctx, "targets")))))))
                .then(Commands.literal("pitch")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.0f))
                                        .executes(ctx -> setPitch(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "pitch"), null))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> setPitch(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "pitch"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("speed")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
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
                        .then(Commands.argument("priority", IntegerArgumentType.integer(0))
                                .executes(ctx -> setPriority(ctx, StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "priority"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setPriority(ctx, StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "priority"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("min-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("min-distance", FloatArgumentType.floatArg(0.0f))
                                .executes(ctx -> setMinDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "min-distance"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setMinDistance(ctx, StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "min-distance"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("bus")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("bus", StringArgumentType.word())
                                .executes(ctx -> setBus(ctx, StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "bus"), null))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> setBus(ctx, StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "bus"), EntityArgument.getPlayers(ctx, "targets")))))))
                        .then(Commands.literal("max-distance")
                .then(Commands.argument("id", StringArgumentType.string())
                        .then(Commands.argument("max-distance", FloatArgumentType.floatArg(0.0f))
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
                .then(bindCmd)
                .then(unbindCmd)
                .then(busCmd)
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

        AuralisAudience audience = authoritativeAudience(ctx, targets, explicitTargets);
        int sent;
        if (isStreamed) {
            sent = AuralisServerApi.playStreamedSound(
                    id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                    priority, minDistance, maxDistance, audience
            );
        } else {
            sent = AuralisServerApi.playSound(
                    id, soundEventId, volume, pitch, speed, isStatic, pos, looping,
                    priority, minDistance, maxDistance, audience
            );
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

        int sent = AuralisServerApi.pauseSound(
                id, authoritativeAudience(ctx, targets, explicitTargets)
        );

        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家发送暂停指令 (id=" + id + ")"),
                false
        );
        return 1;
    }

    private static int resumeSound(CommandContext<CommandSourceStack> ctx, String id, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.resumeSound(
                id, authoritativeAudience(ctx, targets, explicitTargets)
        );
        ctx.getSource().sendSuccess(
                () -> Component.literal(
                        "[GFBS Auralis] 已向 " + sent + " 名玩家发送继续播放指令 (id=" + id + ")"
                ),
                false
        );
        return 1;
    }

    private static int stopSound(CommandContext<CommandSourceStack> ctx, String id, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.stopSound(
                id, authoritativeAudience(ctx, targets, explicitTargets)
        );

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

        int sent = AuralisServerApi.setVolume(
                id, volume, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置音量 (id=" + id + ", volume=" + volume + ")"), false);
        return 1;
    }

    private static int setPitch(CommandContext<CommandSourceStack> ctx, String id, float pitch, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setPitch(
                id, pitch, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置音高 (id=" + id + ", pitch=" + pitch + ")"), false);
        return 1;
    }

    private static int setSpeed(CommandContext<CommandSourceStack> ctx, String id, float speed, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setSpeed(
                id, speed, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置速度 (id=" + id + ", speed=" + speed + ")"), false);
        return 1;
    }

    private static int setPosition(CommandContext<CommandSourceStack> ctx, String id, Vec3 pos, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setPosition(
                id, pos, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置位置 (id=" + id + ", pos=" + pos.x + "," + pos.y + "," + pos.z + ")"), false);
        return 1;
    }

    private static int setStatic(CommandContext<CommandSourceStack> ctx, String id, boolean isStatic, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setStatic(
                id, isStatic, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置静态模式 (id=" + id + ", static=" + isStatic + ")"), false);
        return 1;
    }

    private static int setLooping(CommandContext<CommandSourceStack> ctx, String id, boolean looping, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setLooping(
                id, looping, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置循环 (id=" + id + ", looping=" + looping + ")"), false);
        return 1;
    }

    private static int setPriority(CommandContext<CommandSourceStack> ctx, String id, int priority, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setPriority(
                id, priority, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置优先级 (id=" + id + ", priority=" + priority + ")"), false);
        return 1;
    }

    private static int setMinDistance(CommandContext<CommandSourceStack> ctx, String id, float minDistance, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setMinDistance(
                id, minDistance, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置最小距离 (id=" + id + ", minDistance=" + minDistance + ")"), false);
        return 1;
    }

    private static int setMaxDistance(CommandContext<CommandSourceStack> ctx, String id, float maxDistance, Collection<ServerPlayer> explicitTargets) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;

        int sent = AuralisServerApi.setMaxDistance(
                id, maxDistance, authoritativeAudience(ctx, targets, explicitTargets)
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(() -> Component.literal("[GFBS Auralis] 已向 " + finalSent + " 名玩家设置最大距离 (id=" + id + ", maxDistance=" + maxDistance + ")"), false);
        return 1;
    }

    private static int setBus(
            CommandContext<CommandSourceStack> ctx,
            String id,
            String bus,
            Collection<ServerPlayer> explicitTargets
    ) {
        BusControlPacket packet = new BusControlPacket(
                BusControlPacket.Action.SET_INSTANCE_BUS,
                id,
                bus,
                0f,
                false
        );
        return sendBusOperation(ctx, packet, explicitTargets, "已将音源 " + id + " 路由到总线 " + bus);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> busFlagCommand(
            String literal,
            BusControlPacket.Action action
    ) {
        ArgumentBuilder<CommandSourceStack, ?> value = busTerminal(
                Commands.argument("value", BoolArgumentType.bool()),
                action
        );
        ArgumentBuilder<CommandSourceStack, ?> bus = Commands.argument("bus", StringArgumentType.word());
        bus.then(value);
        ArgumentBuilder<CommandSourceStack, ?> command = Commands.literal(literal);
        command.then(bus);
        return command;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> busTerminal(
            ArgumentBuilder<CommandSourceStack, ?> terminal,
            BusControlPacket.Action action
    ) {
        terminal.executes(ctx -> manageBus(ctx, action, null));
        terminal.then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> manageBus(ctx, action, EntityArgument.getPlayers(ctx, "targets"))));
        return terminal;
    }

    private static int manageBus(
            CommandContext<CommandSourceStack> ctx,
            BusControlPacket.Action action,
            Collection<ServerPlayer> explicitTargets
    ) {
        String bus = StringArgumentType.getString(ctx, "bus");
        String parent = switch (action) {
            case CREATE_BUS, SET_PARENT -> StringArgumentType.getString(ctx, "parent");
            default -> "Master";
        };
        float value = action == BusControlPacket.Action.SET_VOLUME
                ? FloatArgumentType.getFloat(ctx, "value")
                : 0.0f;
        boolean flag = switch (action) {
            case SET_MUTED, SET_SOLO, SET_EFFECTS_BYPASSED -> BoolArgumentType.getBool(ctx, "value");
            default -> false;
        };
        BusControlPacket packet = new BusControlPacket(action, bus, parent, value, flag);
        String detail = switch (action) {
            case CREATE_BUS -> "创建总线 " + bus + " → " + parent;
            case REMOVE_BUS -> "移除总线 " + bus;
            case SET_PARENT -> "设置总线 " + bus + " → " + parent;
            case SET_VOLUME -> "设置总线 " + bus + " 音量为 " + value;
            case SET_MUTED -> "设置总线 " + bus + " 静音=" + flag;
            case SET_SOLO -> "设置总线 " + bus + " 独奏=" + flag;
            case SET_EFFECTS_BYPASSED -> "设置总线 " + bus + " 效果旁路=" + flag;
            case SET_INSTANCE_BUS -> "";
        };
        return sendBusOperation(ctx, packet, explicitTargets, detail);
    }

    private static int sendBusOperation(
            CommandContext<CommandSourceStack> ctx,
            BusControlPacket packet,
            Collection<ServerPlayer> explicitTargets,
            String detail
    ) {
        Collection<ServerPlayer> targets = resolveTargets(ctx, explicitTargets);
        if (targets == null) return 0;
        int sent = AuralisServerManager.applyBusControl(
                authoritativeAudience(ctx, targets, explicitTargets), packet
        );
        int finalSent = sent;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[GFBS Auralis] " + detail + "（" + finalSent + " 名玩家）"),
                false
        );
        return sent;
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

        int sent = AuralisServerApi.tween(
                id, property, value, duration, easingStyle, easingDirection,
                authoritativeAudience(ctx, targets, explicitTargets)
        );

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

        int sent = AuralisServerApi.tweenPosition(
                id, targetPos, duration, easingStyle, easingDirection,
                authoritativeAudience(ctx, targets, explicitTargets)
        );

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

    private static AuralisAudience authoritativeAudience(
            CommandContext<CommandSourceStack> ctx,
            Collection<ServerPlayer> resolvedTargets,
            Collection<ServerPlayer> explicitTargets
    ) {
        if (explicitTargets != null) {
            for (var node : ctx.getNodes()) {
                if (!"targets".equals(node.getNode().getName())) continue;
                String selector = ctx.getInput().substring(
                        node.getRange().getStart(), node.getRange().getEnd()
                );
                if ("@a".equals(selector)) return AuralisAudience.all();
            }
        }
        return AuralisAudience.players(resolvedTargets);
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

    private static int bindEntityCmd(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        net.minecraft.world.entity.Entity target = EntityArgument.getEntity(ctx, "target_entity");
        Collection<ServerPlayer> targets = (explicitTargets != null) ? explicitTargets : Collections.singleton(ctx.getSource().getPlayerOrException());

        int sent = AuralisServerApi.bindEntity(
                id, target.getId(), target.getUUID(),
                authoritativeAudience(ctx, targets, explicitTargets)
        );
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[GFBS Auralis] 已为 " + sent + " 名玩家将音源 " + id
                        + " 绑定到实体 " + target.getName().getString()
                        + " (uuid=" + target.getUUID() + ")"
        ), true);
        return 1;
    }

    private static int bindBlockCmd(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        net.minecraft.core.BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        Collection<ServerPlayer> targets = (explicitTargets != null) ? explicitTargets : Collections.singleton(ctx.getSource().getPlayerOrException());

        int sent = AuralisServerApi.bindBlock(
                id, pos, authoritativeAudience(ctx, targets, explicitTargets)
        );
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[GFBS Auralis] 已为 " + sent + " 名玩家将音源 " + id
                        + " 绑定到方块位置 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
        ), true);
        return 1;
    }

    private static int unbindCmd(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> explicitTargets) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        Collection<ServerPlayer> targets = (explicitTargets != null) ? explicitTargets : Collections.singleton(ctx.getSource().getPlayerOrException());

        int sent = AuralisServerApi.unbind(
                id, authoritativeAudience(ctx, targets, explicitTargets)
        );
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[GFBS Auralis] 已为 " + sent + " 名玩家解除音源 " + id + " 的绑定"
        ), true);
        return 1;
    }

    private static int unsupportedOnServer(CommandContext<CommandSourceStack> ctx, String sub) {
        ctx.getSource().sendFailure(Component.literal("[GFBS Auralis] /auralis " + sub + " 无法在服务器侧直接查询客户端音源状态（此版本已改为通过网络在客户端播放/控制）。"));
        ctx.getSource().sendFailure(Component.literal("[GFBS Auralis] 你可以在客户端日志里查看播放/报错信息。"));
        return 0;
    }
}
