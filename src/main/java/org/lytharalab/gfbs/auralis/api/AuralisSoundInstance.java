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
import net.minecraft.world.phys.Vec3;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;

import java.util.List;

public interface AuralisSoundInstance {
    static void bind(AuralisSoundInstance instance) {
        AuralisApi.engine().bind(instance);
    }

    static void unbind(AuralisSoundInstance instance) {
        AuralisApi.engine().unbind(instance);
    }

    void play();
    void pause();
    void stop();

    /**
     * Returns the logical playback state. Since 2.1.0 this can remain {@code true}
     * while the instance is virtual and therefore owns no physical OpenAL Source.
     */
    boolean isPlaying();

    boolean isPaused();

    /**
     * 添加一个音频处理器
     * @param processor 处理器实例
     * @return this
     */
    AuralisSoundInstance addProcessor(AudioProcessor processor);

    default AuralisSoundInstance removeProcessor(AudioProcessor processor) {
        return this;
    }

    default AuralisSoundInstance clearProcessors() {
        return this;
    }

    default List<AudioProcessor> getProcessors() {
        return List.of();
    }

    /**
     * Returns whether this logical instance currently owns a physical OpenAL Source.
     * A playing 2.1.0 instance may legitimately return {@code false} here while virtual.
     */
    boolean isBound();

    /**
     * Returns true when the logical voice is playing but currently has no physical
     * OpenAL Source. Virtual voices continue advancing their playback timeline.
     */
    default boolean isVirtual() {
        return isPlaying() && !isBound();
    }

    /**
     * Current authoritative logical playback position in source-media seconds.
     * This advances while the voice is virtual.
     */
    default double getPlaybackPositionSeconds() {
        return 0.0;
    }

    /**
     * Seek the authoritative logical cursor in source-media seconds.
     * Implementations must marshal physical decoder/OpenAL work to their audio
     * worker rather than blocking the caller (which may be a client tick thread).
     */
    default AuralisSoundInstance setPlaybackPositionSeconds(double seconds) {
        return this;
    }

    /** Duration of the resolved audio resource in seconds, or 0 when unknown. */
    default double getDurationSeconds() {
        return 0.0;
    }

    AuralisSoundInstance setVolume(float volume);
    float getVolume();

    AuralisSoundInstance setPitch(float pitch);
    float getPitch();

    AuralisSoundInstance setSpeed(float speed);
    float getSpeed();

    AuralisSoundInstance setStatic(boolean isStatic);
    boolean isStatic();

    AuralisSoundInstance setPosition(Vec3 pos);
    Vec3 getPosition();

    AuralisSoundInstance setMinDistance(float dist);
    float getMinDistance();

    AuralisSoundInstance setMaxDistance(float dist);
    float getMaxDistance();

    AuralisSoundInstance setLooping(boolean looping);
    boolean isLooping();

    /**
     * Controls whether this instance is permanently disposed after a non-looping playback
     * reaches its natural end. The default is {@code true} for backwards compatibility.
     * <p>
     * When disabled, Auralis still releases the scarce OpenAL source after playback ends,
     * but keeps the logical instance and its audio resources alive so {@link #play()} can
     * start it again. Call {@link #unbind(AuralisSoundInstance)} when the instance is no
     * longer needed.
     *
     * @param enabled {@code true} to dispose automatically; {@code false} to retain the instance
     * @return this instance
     */
    default AuralisSoundInstance setAutoDisposeOnFinish(boolean enabled) {
        return this;
    }

    /**
     * @return whether this instance is automatically disposed after natural playback completion
     */
    default boolean isAutoDisposeOnFinish() {
        return true;
    }

    AuralisSoundInstance setPriority(int priority);

    int getPriority();

    /** Route this voice into a named hierarchical audio bus. */
    default AuralisSoundInstance setBus(String busName) {
        return this;
    }

    default String getBus() {
        return org.lytharalab.gfbs.auralis.api.bus.AudioBusSystem.MASTER;
    }

    AuralisSoundInstance addListener(AuralisSoundListener listener);

    AuralisSoundInstance removeListener(AuralisSoundListener listener);
}
