package org.lytharalab.gfbs.auralis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import org.lytharalab.gfbs.auralis.api.*;
import org.lytharalab.gfbs.auralis.network.BusControlPacket;
import org.lytharalab.gfbs.auralis.network.NetworkHandler;

import java.util.Collection;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;

/** OP-facing commands backed by the same authoritative API used by integrations. */
public final class SoundCommand {
    private SoundCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(root("auralis"));
        dispatcher.register(root("gfbs_auralis"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .then(createCommand("play", false))
                .then(createCommand("stream", true))
                .then(Commands.literal("pause").then(idArgument().executes(ctx -> operate(ctx.getSource(), id(ctx), ServerSoundInstance::pause))))
                .then(Commands.literal("stop").then(idArgument().executes(ctx -> operate(ctx.getSource(), id(ctx), ServerSoundInstance::stop))))
                .then(Commands.literal("dispose").then(idArgument().executes(ctx -> operate(ctx.getSource(), id(ctx), ServerSoundInstance::dispose))))
                .then(Commands.literal("seek").then(idArgument().then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> operate(ctx.getSource(), id(ctx), sound -> sound.seek(DoubleArgumentType.getDouble(ctx, "seconds")))))))
                .then(Commands.literal("global").then(idArgument().executes(ctx -> operate(ctx.getSource(), id(ctx), AuralisServerApi::setGlobal))))
                .then(Commands.literal("audience").then(idArgument().then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> clearGlobal(ctx.getSource(), id(ctx), EntityArgument.getPlayers(ctx, "targets"))))))
                .then(Commands.literal("volume").then(idArgument().then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 16.0f))
                        .executes(ctx -> update(ctx.getSource(), id(ctx), spec -> copy(spec, FloatArgumentType.getFloat(ctx, "value"), spec.pitch(), spec.speed(), spec.listenerRelative(), spec.position(), spec.looping(), spec.priority()))))))
                .then(Commands.literal("pitch").then(idArgument().then(Commands.argument("value", FloatArgumentType.floatArg(0.01f, 8.0f))
                        .executes(ctx -> update(ctx.getSource(), id(ctx), spec -> copy(spec, spec.volume(), FloatArgumentType.getFloat(ctx, "value"), spec.speed(), spec.listenerRelative(), spec.position(), spec.looping(), spec.priority()))))))
                .then(Commands.literal("speed").then(idArgument().then(Commands.argument("value", FloatArgumentType.floatArg(0.01f, 8.0f))
                        .executes(ctx -> update(ctx.getSource(), id(ctx), spec -> copy(spec, spec.volume(), spec.pitch(), FloatArgumentType.getFloat(ctx, "value"), spec.listenerRelative(), spec.position(), spec.looping(), spec.priority()))))))
                .then(Commands.literal("looping").then(idArgument().then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> update(ctx.getSource(), id(ctx), spec -> copy(spec, spec.volume(), spec.pitch(), spec.speed(), spec.listenerRelative(), spec.position(), BoolArgumentType.getBool(ctx, "value"), spec.priority()))))))
                .then(Commands.literal("position").then(idArgument().then(Commands.argument("position", Vec3Argument.vec3())
                        .executes(ctx -> update(ctx.getSource(), id(ctx), spec -> copy(spec, spec.volume(), spec.pitch(), spec.speed(), false, Vec3Argument.getVec3(ctx, "position"), spec.looping(), spec.priority()))))))
                .then(busCommands())
        ;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> busCommands() {
        return Commands.literal("bus")
                .then(Commands.literal("create")
                        .then(Commands.argument("bus", StringArgumentType.word())
                                .then(Commands.argument("parent", StringArgumentType.word())
                                        .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> sendBus(
                                                ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"),
                                                new BusControlPacket(BusControlPacket.Action.CREATE_BUS,
                                                        StringArgumentType.getString(ctx, "bus"), StringArgumentType.getString(ctx, "parent"), 0f, false)))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("bus", StringArgumentType.word())
                                .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> sendBus(
                                        ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"),
                                        new BusControlPacket(BusControlPacket.Action.REMOVE_BUS,
                                                StringArgumentType.getString(ctx, "bus"), "Master", 0f, false))))))
                .then(Commands.literal("parent")
                        .then(Commands.argument("bus", StringArgumentType.word())
                                .then(Commands.argument("parent", StringArgumentType.word())
                                        .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> sendBus(
                                                ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"),
                                                new BusControlPacket(BusControlPacket.Action.SET_PARENT,
                                                        StringArgumentType.getString(ctx, "bus"),
                                                        StringArgumentType.getString(ctx, "parent"), 0f, false)))))))
                .then(Commands.literal("volume")
                        .then(Commands.argument("bus", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0f, 16f))
                                        .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> sendBus(
                                                ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"),
                                                new BusControlPacket(BusControlPacket.Action.SET_VOLUME,
                                                        StringArgumentType.getString(ctx, "bus"), "Master",
                                                        FloatArgumentType.getFloat(ctx, "value"), false)))))))
                .then(busFlagCommand("muted", BusControlPacket.Action.SET_MUTED))
                .then(busFlagCommand("solo", BusControlPacket.Action.SET_SOLO))
                .then(busFlagCommand("bypass", BusControlPacket.Action.SET_EFFECTS_BYPASSED));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> busFlagCommand(
            String name, BusControlPacket.Action action) {
        return Commands.literal(name)
                .then(Commands.argument("bus", StringArgumentType.word())
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .then(Commands.argument("targets", EntityArgument.players()).executes(ctx -> sendBus(
                                        ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"),
                                        new BusControlPacket(action, StringArgumentType.getString(ctx, "bus"), "Master", 0f,
                                                BoolArgumentType.getBool(ctx, "value")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> createCommand(String name, boolean streamed) {
        return Commands.literal(name)
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("sound", ResourceLocationArgument.id())
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                                ResourceLocationArgument.getId(ctx, "sound"),
                                                EntityArgument.getPlayers(ctx, "targets"), streamed)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
        return Commands.argument("id", StringArgumentType.word());
    }

    private static String id(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "id");
    }

    private static int create(CommandSourceStack source, String id, ResourceLocation eventId,
                              Collection<ServerPlayer> targets, boolean streamed) {
        AuralisSoundSpec spec = AuralisSoundSpec.builder(eventId).streamed(streamed).build();
        AuralisServerApi.create(source.getServer(), id, spec, targets).future()
                .thenCompose(instance -> instance.play().future())
                .whenComplete((result, failure) -> report(source, id, result, failure));
        return 1;
    }

    private static int operate(CommandSourceStack source, String id,
                               java.util.function.Function<ServerSoundInstance, AuralisOperation<ServerOperationResult>> action) {
        ServerSoundInstance instance = AuralisServerApi.find(source.getServer(), id).orElse(null);
        if (instance == null) {
            source.sendFailure(Component.literal("Unknown Auralis sound: " + id));
            return 0;
        }
        action.apply(instance).onComplete((result, failure) -> report(source, id, result, failure));
        return 1;
    }

    private static int update(CommandSourceStack source, String id, UnaryOperator<AuralisSoundSpec> transform) {
        return operate(source, id, sound -> {
            AuthoritativeSoundSnapshot snapshot = sound.snapshot().orElseThrow();
            return sound.update(transform.apply(snapshot.spec()));
        });
    }

    private static int clearGlobal(CommandSourceStack source, String id, Collection<ServerPlayer> targets) {
        return operate(source, id, sound -> AuralisServerApi.clearGlobal(sound, targets));
    }

    private static int sendBus(CommandSourceStack source, Collection<ServerPlayer> targets, BusControlPacket packet) {
        int sent = 0;
        for (ServerPlayer player : targets) {
            NetworkHandler.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            sent++;
        }
        int count = sent;
        source.sendSuccess(() -> Component.literal("Auralis bus operation sent to " + count + " client(s)"), true);
        return sent;
    }

    private static AuralisSoundSpec copy(AuralisSoundSpec spec, float volume, float pitch, float speed,
                                         boolean relative, Vec3 position, boolean looping, int priority) {
        return new AuralisSoundSpec(spec.soundEventId(), spec.streamed(), volume, pitch, speed, relative,
                position, looping, priority, spec.minDistance(), spec.maxDistance(), spec.bus());
    }

    private static void report(CommandSourceStack source, String id, ServerOperationResult result, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            source.sendFailure(Component.literal("Auralis operation failed for " + id + ": " + cause.getMessage()));
            return;
        }
        long failures = result.clients().values().stream().filter(client -> client.status() != ClientExecutionStatus.APPLIED
                && client.status() != ClientExecutionStatus.STALE).count();
        source.sendSuccess(() -> Component.literal(failures == 0
                ? "Auralis operation committed: " + id
                : "Auralis operation committed with " + failures + " client failure(s): " + id), true);
    }
}
