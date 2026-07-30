package org.lytharalab.gfbs.auralis.tween;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeTweenHook {

    public static final TweenService TWEENS = new TweenService();

    private static long lastNanoTime = System.nanoTime();

    private ForgeTweenHook() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;

        long now = System.nanoTime();
        double dt = (now - lastNanoTime) / 1_000_000_000.0;
        lastNanoTime = now;

        if (dt > 0.25) dt = 0.25;

        TWEENS.tick(dt);
    }
}
