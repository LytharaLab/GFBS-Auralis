package org.lytharalab.gfbs.auralis;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lytharalab.gfbs.auralis.api.AuralisApi;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GFBsAuralis.MODID)
public class GFBsAuralis {
    public static final String MODID = "gfbs_auralis";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean JVM_HOOK_INSTALLED = new AtomicBoolean(false);

    public GFBsAuralis() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GFBsAuralisConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, GFBsAuralisConfig.CLIENT_SPEC);

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("GFBS-Auralis Startup...");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ServerSetup::init);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            event.enqueueWork(() -> {
                try {
                    installJvmShutdownHook();
                    var cfg = GFBsAuralisConfig.CLIENT;
                    AuralisAL al = AuralisAL.createAndStartGlobal(AuralisAL.Config.defaultsWithHrtf(cfg.enableHrtf.get()));
                    int configuredMaxSources = cfg.maxSources.get();
                    int reserve = cfg.reserveSourcesForVanilla.get();
                    AuralisAL.SourceBudget sourceBudget = al.querySourceBudget();
                    int effectiveMaxSources = al.recommendSourcePoolLimit(configuredMaxSources, reserve);
                    AuralisEngine engine = new AuralisEngine(
                            Minecraft.getInstance(),
                            al,
                            effectiveMaxSources,
                            cfg.streamedChunkSize.get(),
                            cfg.maxStreamedBytes.get(),
                            cfg.attenuationExponent.get().floatValue(),
                            cfg.volumeSmoothing.get().floatValue(),
                            cfg.voiceMaterializeGain.get().floatValue(),
                            cfg.voiceVirtualizeGain.get().floatValue()
                    );
                    AuralisApi.setEngine(engine);
                    LOGGER.info(
                            "Auralis engine initialized (client). maxSources={} (configured={}, reserveForVanilla={}, deviceMonoSources={}, deviceStereoSources={}, voiceMaterializeGain={}, voiceVirtualizeGain={})",
                            effectiveMaxSources,
                            configuredMaxSources,
                            reserve,
                            sourceBudget.monoSources(),
                            sourceBudget.stereoSources(),
                            cfg.voiceMaterializeGain.get(),
                            cfg.voiceVirtualizeGain.get()
                    );
                } catch (Throwable startupFailure) {
                    LOGGER.error("GFBS-Auralis client engine initialization failed; disabling Auralis for this session", startupFailure);
                    try {
                        AuralisAL.stopAndClearGlobal();
                    } catch (Throwable cleanupFailure) {
                        startupFailure.addSuppressed(cleanupFailure);
                    }
                }
            });
        });
    }

    private static void installJvmShutdownHook() {
        if (!JVM_HOOK_INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                AuralisAL.stopAndClearGlobal();
            } catch (Throwable t) {
                LOGGER.warn("Error stopping AuralisAL during JVM shutdown: {}", String.valueOf(t.getMessage()), t);
            }
        }, "AuralisAL-JvmShutdownHook"));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("GFBS-Auralis server starting...");
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT
    )
    public static class ClientModEvents {
        public static AuralisEngine engine;
        private static final AtomicBoolean ENGINE_RUNTIME_FAILED = new AtomicBoolean(false);

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            if (ENGINE_RUNTIME_FAILED.get()) return;
            try {
                ClientSoundController.flushPendingIfReady();
                var mc = Minecraft.getInstance();
                if (mc.level != null) {
                    ClientSoundController.tickBoundPositions(mc.level, mc.level.entitiesForRendering());
                }
                if (AuralisApi.isInitialized()) {
                    AuralisApi.engine().tick();
                }
                ClientSoundController.pruneFinishedInstances();
            } catch (Throwable runtimeFailure) {
                if (runtimeFailure instanceof VirtualMachineError fatal) throw fatal;
                if (runtimeFailure instanceof ThreadDeath fatal) throw fatal;
                if (ENGINE_RUNTIME_FAILED.compareAndSet(false, true)) {
                    LOGGER.error("GFBS-Auralis runtime failure; disabling the client audio engine instead of crashing the game", runtimeFailure);
                    try {
                        ClientSoundController.stopAll();
                    } catch (Throwable shutdownFailure) {
                        runtimeFailure.addSuppressed(shutdownFailure);
                    }
                    try {
                        if (AuralisApi.isInitialized() && AuralisApi.engine() instanceof AuralisEngine impl) {
                            impl.shutdown(true);
                        } else {
                            AuralisAL.stopAndClearGlobal();
                        }
                    } catch (Throwable shutdownFailure) {
                        runtimeFailure.addSuppressed(shutdownFailure);
                    }
                }
            }
        }

        @SubscribeEvent
        public static void onClientShutdown(GameShuttingDownEvent e){
            try {
                ClientSoundController.stopAll();
                if (AuralisApi.isInitialized()) {
                    var eng = AuralisApi.engine();
                    eng.shutdown();
                }
            } catch (Throwable shutdownFailure) {
                LOGGER.warn("GFBS-Auralis did not shut down cleanly; forcing OpenAL teardown", shutdownFailure);
                try {
                    AuralisAL.stopAndClearGlobal();
                } catch (Throwable forcedTeardownFailure) {
                    shutdownFailure.addSuppressed(forcedTeardownFailure);
                    LOGGER.warn("Forced Auralis OpenAL teardown also failed", forcedTeardownFailure);
                }
            }
        }

        @SubscribeEvent
        public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
            ClientSoundController.stopAll();
        }
    }
}
