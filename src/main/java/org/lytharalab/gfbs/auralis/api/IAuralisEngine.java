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

import java.util.concurrent.CompletableFuture;

public interface IAuralisEngine {
    AuralisSoundInstance create(SoundEvent soundEvent);
    AuralisSoundInstance createStreamed(SoundEvent soundEvent);

    CompletableFuture<AuralisSoundInstance> createAsync(SoundEvent soundEvent);
    CompletableFuture<AuralisSoundInstance> createStreamedAsync(SoundEvent soundEvent);

    void bind(AuralisSoundInstance instance);
    void unbind(AuralisSoundInstance instance);

    void tick();

    /** Total retained logical instances known to the engine. */
    default int getLogicalVoiceCount() { return 0; }

    /** Logical voices whose playback clocks are currently advancing. */
    default int getPlayingVoiceCount() { return 0; }

    /** Playing voices currently backed by physical OpenAL Sources. */
    default int getPhysicalVoiceCount() { return 0; }

    /** Playing voices currently virtualized without an OpenAL Source. */
    default int getVirtualVoiceCount() { return 0; }

    void shutdown();
}
