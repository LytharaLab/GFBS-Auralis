package org.mirage.gfbs.auralis.api;
/**
 * G.F.B.S.-Auralis (gfbs_auralis) - A Minecraft Mod
 * Copyright (C) 2025-2029 Mirage-MC
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
import org.mirage.gfbs.auralis.api.processing.AudioProcessor;

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
    boolean isPlaying();
    boolean isPaused();

    /**
     * 添加一个音频处理器
     * @param processor 处理器实例
     * @return this
     */
    AuralisSoundInstance addProcessor(AudioProcessor processor);

    boolean isBound();

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

    AuralisSoundInstance setPriority(int priority);

    int getPriority();

    AuralisSoundInstance addListener(AuralisSoundListener listener);

    AuralisSoundInstance removeListener(AuralisSoundListener listener);
}
