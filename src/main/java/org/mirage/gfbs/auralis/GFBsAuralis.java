package org.mirage.gfbs.auralis;

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
import org.mirage.gfbs.auralis.api.AuralisApi;
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
                        cfg.volumeSmoothing.get().floatValue()
                );
                AuralisApi.setEngine(engine);
                LOGGER.info(
                        "Auralis engine initialized (client). maxSources={} (configured={}, reserveForVanilla={}, deviceMonoSources={}, deviceStereoSources={})",
                        effectiveMaxSources,
                        configuredMaxSources,
                        reserve,
                        sourceBudget.monoSources(),
                        sourceBudget.stereoSources()
                );
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

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            ClientSoundController.flushPendingIfReady();
            var mc = Minecraft.getInstance();
            if (mc.level != null) {
                ClientSoundController.tickBoundPositions(mc.level, mc.level.entitiesForRendering());
            }
            if (AuralisApi.isInitialized()) {
                AuralisApi.engine().tick();
            }
        }

        @SubscribeEvent
        public static void onClientShutdown(GameShuttingDownEvent e){
            if (AuralisApi.isInitialized()) {
                var eng = AuralisApi.engine();
                if (eng instanceof AuralisEngine impl) {
                    impl.shutdown(false);
                } else {
                    eng.shutdown();
                }
            }
        }

        @SubscribeEvent
        public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
            ClientSoundController.stopAll();
        }
    }
}
