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
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectRegistry;
import org.lytharalab.gfbs.auralis.api.openal.OpenALAccess;
import org.lytharalab.gfbs.auralis.api.plugin.AuralisPlugin;
import org.lytharalab.gfbs.auralis.api.plugin.AuralisPluginService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AuralisApi {
    private static volatile @Nullable IAuralisEngine ENGINE;
    private static final Map<String, AuralisPlugin> REGISTERED_PLUGINS = new ConcurrentHashMap<>();

    private AuralisApi() {}

    public static synchronized void setEngine(IAuralisEngine engine) {
        ENGINE = java.util.Objects.requireNonNull(engine, "engine");
        if (!REGISTERED_PLUGINS.isEmpty()) {
            engine.plugins().loadAll(REGISTERED_PLUGINS.values());
        }
    }

    /** Clear the engine only if it is still the instance being shut down. */
    public static synchronized boolean clearEngine(IAuralisEngine expected) {
        if (ENGINE != expected) return false;
        ENGINE = null;
        return true;
    }

    public static IAuralisEngine engine() {
        IAuralisEngine e = ENGINE;
        if (e == null) {
            throw new IllegalStateException(
                    "Auralis engine not initialized. This is likely because: " +
                            "1. You're trying to use Auralis on the server side (it's client-only) " +
                            "2. The mod hasn't finished loading yet " +
                            "3. The engine initialization failed for some reason"
            );
        }
        return e;
    }

    public static boolean isInitialized() {
        return ENGINE != null;
    }

    public static AudioBusSystem buses() { return engine().buses(); }
    public static AuralisEffectRegistry effects() { return engine().effects(); }
    public static AuralisPluginService plugins() { return engine().plugins(); }
    public static OpenALAccess openAL() { return engine().openAL(); }

    /**
     * Registers a plugin safely before or after client-engine initialization.
     * Forge mods may call this from client setup without relying on ServiceLoader.
     */
    public static boolean registerPlugin(AuralisPlugin plugin) {
        java.util.Objects.requireNonNull(plugin, "plugin");
        String id = normalizePluginId(plugin.getId());
        AuralisPlugin previous = REGISTERED_PLUGINS.putIfAbsent(id, plugin);
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException("Auralis plugin id already registered: " + id);
        }
        if (previous == plugin) return false;
        IAuralisEngine engine = ENGINE;
        if (engine == null) return true;
        boolean loaded = engine.plugins().load(plugin);
        if (loaded) {
            // A newly enabled dependency may unblock registrations accepted
            // earlier in the runtime.
            engine.plugins().loadAll(REGISTERED_PLUGINS.values());
        }
        return loaded;
    }

    /** Removes a queued registration and unloads its active plugin instance. */
    public static boolean unregisterPlugin(String pluginId) {
        String id = normalizePluginId(pluginId);
        boolean removed = REGISTERED_PLUGINS.remove(id) != null;
        IAuralisEngine engine = ENGINE;
        return engine != null ? engine.plugins().unload(id) || removed : removed;
    }

    /**
     * Retries queued explicit registrations after another discovery source has
     * loaded. This resolves dependencies that cross the explicit/ServiceLoader
     * boundary without exposing the mutable registration collection.
     */
    public static int loadRegisteredPlugins() {
        IAuralisEngine engine = ENGINE;
        return engine == null || REGISTERED_PLUGINS.isEmpty()
                ? 0
                : engine.plugins().loadAll(REGISTERED_PLUGINS.values());
    }

    private static String normalizePluginId(String pluginId) {
        String id = java.util.Objects.requireNonNull(pluginId, "pluginId")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty() || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Plugin id must be namespaced (namespace:path): " + pluginId);
        }
        return id;
    }

    public static AuralisSoundInstance create(SoundEvent soundEvent) {
        IAuralisEngine engine = ENGINE;
        if (engine == null) {
            return new ServerPlaceholderSoundInstance();
        }
        return engine.create(soundEvent);
    }

    public static AuralisSoundInstance createStreamed(SoundEvent soundEvent) {
        IAuralisEngine engine = ENGINE;
        if (engine == null) {
            return new ServerPlaceholderSoundInstance();
        }
        return engine.createStreamed(soundEvent);
    }

    public static CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent) {
        IAuralisEngine engine = ENGINE;
        if (engine == null) {
            return CompletableFuture.completedFuture(new ServerPlaceholderSoundInstance());
        }
        return engine.createAsync(soundEvent);
    }

    public static CompletableFuture<AuralisSoundInstance> createStreamedAsync(SoundEvent soundEvent) {
        IAuralisEngine engine = ENGINE;
        if (engine == null) {
            return CompletableFuture.completedFuture(new ServerPlaceholderSoundInstance());
        }
        return engine.createStreamedAsync(soundEvent);
    }

    static class ServerPlaceholderSoundInstance implements AuralisSoundInstance {
        @Override public void play() {}
        @Override public void pause() {}
        @Override public void stop() {}
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isPaused() { return false; }
        @Override public boolean isBound() { return false; }
        @Override public AuralisSoundInstance setVolume(float volume) { return this; }
        @Override public float getVolume() { return 1.0f; }
        @Override public AuralisSoundInstance setPitch(float pitch) { return this; }
        @Override public float getPitch() { return 1.0f; }
        @Override public AuralisSoundInstance setSpeed(float speed) { return this; }
        @Override public float getSpeed() { return 1.0f; }
        @Override public AuralisSoundInstance setStatic(boolean isStatic) { return this; }
        @Override public boolean isStatic() { return false; }
        @Override public AuralisSoundInstance setPosition(net.minecraft.world.phys.Vec3 pos) { return this; }
        @Override public net.minecraft.world.phys.Vec3 getPosition() { return net.minecraft.world.phys.Vec3.ZERO; }
        @Override public AuralisSoundInstance setMinDistance(float dist) { return this; }
        @Override public float getMinDistance() { return 1.0f; }
        @Override public AuralisSoundInstance setMaxDistance(float dist) { return this; }
        @Override public float getMaxDistance() { return 48.0f; }
        @Override public AuralisSoundInstance setLooping(boolean looping) { return this; }
        @Override public boolean isLooping() { return false; }
        @Override public AuralisSoundInstance setAutoDisposeOnFinish(boolean enabled) { return this; }
        @Override public boolean isAutoDisposeOnFinish() { return true; }
        @Override public AuralisSoundInstance setPriority(int priority) { return this; }
        @Override public int getPriority() { return 50; }
        @Override public AuralisSoundInstance addListener(AuralisSoundListener listener) { return this; }
        @Override public AuralisSoundInstance removeListener(AuralisSoundListener listener) { return this; }
        @Override public AuralisSoundInstance addProcessor(org.lytharalab.gfbs.auralis.api.processing.AudioProcessor processor) { return this; }
    }
}
