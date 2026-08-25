package org.lytharalab.gfbs.auralis.utils;
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
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.auralis.DecodedPcm;

import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class OggVorbisDecoder {
    private OggVorbisDecoder() {}

    // 16-bit PCM range
    private static final float PCM16_MAX = 32767.0f;
    private static final float PCM16_MIN = -32768.0f;

    // Chunk sizes tuned for low GC & good throughput.
    private static final int FULL_DECODE_FRAMES_PER_CHUNK = 8192;
    private static final int STREAM_DECODE_FRAMES_PER_CHUNK = 4096;
    private static final int MAX_FULL_DECODE_COMPRESSED_BYTES = 256 * 1024 * 1024;
    private static final int MAX_DECODED_PCM_BYTES = 256 * 1024 * 1024;
    private static final int MAX_INITIAL_PCM_BYTES = 16 * 1024 * 1024;

    public static DecodedPcm decodeFully(InputStream in) throws Exception {
        ByteBuffer ogg = readAllToNative(in, MAX_FULL_DECODE_COMPRESSED_BYTES);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            long handle = STBVorbis.stb_vorbis_open_memory(ogg, error, null);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("stb_vorbis_open_memory failed, error=" + error.get(0));
            }

            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                int inChannels = info.channels();
                int sampleRate = info.sample_rate();
                if (inChannels <= 0 || inChannels > 255 || sampleRate <= 0 || sampleRate > 768_000) {
                    throw new IllegalStateException("Invalid OGG/Vorbis info: channels=" + inChannels + ", sampleRate=" + sampleRate);
                }

                // OpenAL AL10 only guarantees mono/stereo. For >2ch Vorbis, downmix to stereo.
                int outChannels = (inChannels <= 1) ? 1 : 2;
                int alFormat = (outChannels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

                // stb_vorbis_stream_length_in_samples can be 0/-1 for some edge cases.
                int lengthInSamplesPerChannel = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                int estimatedFrames = (lengthInSamplesPerChannel > 0)
                        ? lengthInSamplesPerChannel
                        : safeMul(sampleRate, 2);
                long reportedBytes = (long) estimatedFrames * outChannels * 2L;
                int estimatedBytes = (int) Math.max(
                        4_096L,
                        Math.min((long) MAX_INITIAL_PCM_BYTES, Math.min((long) MAX_DECODED_PCM_BYTES, reportedBytes))
                );

                ByteBuffer pcm = MemoryUtil.memAlloc(estimatedBytes);
                FloatBuffer inFloat = null;

                try {
                    pcm.order(ByteOrder.nativeOrder());
                    ShortBuffer pcmShort = pcm.asShortBuffer();
                    int writePosBytes = 0;

                    // Decode in float, then dither+convert to 16-bit PCM.
                    int framesPerChunk = FULL_DECODE_FRAMES_PER_CHUNK;
                    inFloat = MemoryUtil.memAllocFloat(safeMul(framesPerChunk, inChannels));
                    Dither dither = new Dither();
                    float[] downmixTmp = (inChannels > 2) ? new float[framesPerChunk * 2] : null;
                    float downmixGain = 1.0f;

                    while (true) {
                        inFloat.clear();
                        int framesDecoded = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, inChannels, inFloat);
                        if (framesDecoded <= 0) break;

                        int neededBytes = safeMul(safeMul(framesDecoded, outChannels), 2);
                        int requiredBytes = safeAdd(writePosBytes, neededBytes);
                        if (requiredBytes > MAX_DECODED_PCM_BYTES) {
                            throw new IllegalStateException(
                                    "Decoded OGG/Vorbis PCM exceeds the " + MAX_DECODED_PCM_BYTES + " byte safety limit"
                            );
                        }
                        if (requiredBytes > pcm.capacity()) {
                            int newCap = Math.min(
                                    MAX_DECODED_PCM_BYTES,
                                    growCapacity(pcm.capacity(), requiredBytes)
                            );
                            ByteBuffer grown = MemoryUtil.memAlloc(newCap);
                            grown.order(ByteOrder.nativeOrder());
                            boolean transferred = false;
                            try {
                                pcm.position(0).limit(writePosBytes);
                                grown.put(pcm);
                                ShortBuffer grownShort = grown.asShortBuffer();
                                ByteBuffer previous = pcm;
                                pcm = grown;
                                pcmShort = grownShort;
                                transferred = true;
                                MemoryUtil.memFree(previous);
                            } finally {
                                if (!transferred) MemoryUtil.memFree(grown);
                            }
                        }

                        pcmShort.position(writePosBytes >> 1);

                        inFloat.position(0).limit(framesDecoded * inChannels);
                        if (inChannels == outChannels) {
                            // 1->1 or 2->2
                            convertFloatToPcm16Interleaved(inFloat, pcmShort, framesDecoded, outChannels, dither);
                        } else if (inChannels == 1 && outChannels == 2) {
                            // Mono -> stereo duplicate.
                            convertMonoToStereoPcm16(inFloat, pcmShort, framesDecoded, dither);
                        } else {
                            // >2ch -> stereo downmix.
                            downmixToStereo(inFloat, framesDecoded, inChannels, downmixTmp);
                            downmixGain = applyLimiterStereoGain(downmixTmp, framesDecoded, downmixGain);
                            convertFloatArrayToPcm16Interleaved(downmixTmp, pcmShort, framesDecoded, 2, dither);
                        }

                        writePosBytes += neededBytes;
                    }

                    if (writePosBytes <= 0) {
                        throw new IllegalStateException("OGG/Vorbis stream decoded no PCM samples");
                    }
                    pcm.position(0).limit(writePosBytes);
                    return new DecodedPcm(alFormat, sampleRate, pcm);
                } catch (Throwable e) {
                    MemoryUtil.memFree(pcm);
                    rethrow(e);
                    throw new AssertionError("unreachable");
                } finally {
                    if (inFloat != null) MemoryUtil.memFree(inFloat);
                }
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        } finally {
            MemoryUtil.memFree(ogg);
        }
    }

    public static StreamDecoder createStreamDecoder(InputStream in) throws Exception {
        return new StreamDecoder(in, MAX_FULL_DECODE_COMPRESSED_BYTES);
    }

    public static StreamDecoder createStreamDecoder(InputStream in, int maxCompressedBytes) throws Exception {
        if (maxCompressedBytes <= 0) {
            throw new IllegalArgumentException("maxCompressedBytes must be positive");
        }
        return new StreamDecoder(in, maxCompressedBytes);
    }

    private static ByteBuffer readAllToNative(InputStream in, int maxBytes) throws Exception {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        int cap = Math.min(64 * 1024, maxBytes);
        ByteBuffer out = MemoryUtil.memAlloc(cap);
        byte[] tmp = new byte[8192];
        int total = 0;
        try {
            while (true) {
                int r = in.read(tmp);
                if (r < 0) break;
                total += r;
                if (total < 0 || total > maxBytes) {
                    throw new IllegalStateException("OGG data exceeds limit");
                }
                if (total > out.capacity()) {
                    int newCap = Math.min(maxBytes, growCapacity(out.capacity(), total));
                    ByteBuffer grown = MemoryUtil.memAlloc(newCap);
                    boolean transferred = false;
                    try {
                        out.position(0).limit(total - r);
                        grown.put(out);
                        ByteBuffer previous = out;
                        out = grown;
                        transferred = true;
                        MemoryUtil.memFree(previous);
                    } finally {
                        if (!transferred) MemoryUtil.memFree(grown);
                    }
                }
                out.put(tmp, 0, r);
            }
            out.flip();
            return out;
        } catch (Throwable e) {
            MemoryUtil.memFree(out);
            rethrow(e);
            throw new AssertionError("unreachable");
        }
    }

    public static class StreamDecoder implements AutoCloseable {
        private final ByteBuffer oggBuffer;
        private final Object nativeLock = new Object();
        private long handle;
        private int inChannels;
        private int outChannels;
        private int sampleRate;
        private int alFormat;
        private boolean isOpen;
        private boolean eof;
        private double durationSeconds;

        private FloatBuffer floatChunk;
        private float[] downmixTmp;
        private final Dither dither = new Dither();
        private float downmixGain = 1.0f;

        private StreamDecoder(InputStream in, int maxCompressedBytes) throws Exception {
            try (InputStream ownedInput = in) {
                oggBuffer = readAllToNative(ownedInput, maxCompressedBytes);
            }

            try {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer error = stack.mallocInt(1);
                    handle = STBVorbis.stb_vorbis_open_memory(oggBuffer, error, null);
                    if (handle == MemoryUtil.NULL) {
                        throw new IllegalStateException("stb_vorbis_open_memory failed, error=" + error.get(0));
                    }

                    STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                    STBVorbis.stb_vorbis_get_info(handle, info);
                    inChannels = info.channels();
                    sampleRate = info.sample_rate();
                    if (inChannels <= 0 || inChannels > 255 || sampleRate <= 0 || sampleRate > 768_000) {
                        throw new IllegalStateException("Invalid OGG/Vorbis info: channels=" + inChannels + ", sampleRate=" + sampleRate);
                    }

                    // AL10 safest output is mono/stereo.
                    outChannels = (inChannels <= 1) ? 1 : 2;
                    alFormat = (outChannels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

                    eof = false;
                    float reportedDuration = STBVorbis.stb_vorbis_stream_length_in_seconds(handle);
                    if (Float.isFinite(reportedDuration) && reportedDuration > 0.0f) {
                        durationSeconds = reportedDuration;
                    } else {
                        int lengthSamples = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                        durationSeconds = lengthSamples > 0
                                ? (lengthSamples / (double) Math.max(1, sampleRate))
                                : 0.0;
                    }

                    int frames = STREAM_DECODE_FRAMES_PER_CHUNK;
                    floatChunk = MemoryUtil.memAllocFloat(safeMul(frames, inChannels));
                    if (inChannels > 2) {
                        downmixTmp = new float[safeMul(frames, 2)];
                    }
                    isOpen = true;
                }
            } catch (Throwable failure) {
                long failedHandle = handle;
                handle = MemoryUtil.NULL;
                if (failedHandle != MemoryUtil.NULL) {
                    try {
                        STBVorbis.stb_vorbis_close(failedHandle);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (floatChunk != null) {
                    try {
                        MemoryUtil.memFree(floatChunk);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    floatChunk = null;
                }
                try {
                    MemoryUtil.memFree(oggBuffer);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
                throw new RuntimeException(failure);
            }
        }

        public int getChannels() {
            return outChannels;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public int getAlFormat() {
            return alFormat;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }

        public int decodeChunk(ByteBuffer output) {
            synchronized (nativeLock) {
                requireOpen();
                if (eof) {
                    return 0;
                }

                output.order(ByteOrder.nativeOrder());

                int bytesPerSample = 2;
                int frameBytes = safeMul(outChannels, bytesPerSample);
                int framesWanted = output.remaining() / frameBytes;
                if (framesWanted <= 0) {
                    return 0;
                }

                int framesPerCall = Math.min(framesWanted, STREAM_DECODE_FRAMES_PER_CHUNK);

                FloatBuffer chunk = floatChunk;
                if (chunk == null) throw new IllegalStateException("Decoder workspace is unavailable");
                chunk.clear();
                chunk.limit(safeMul(framesPerCall, inChannels));

                int framesDecoded = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, inChannels, chunk);
                if (framesDecoded <= 0) {
                    eof = true;
                    return 0;
                }

                int bytesDecoded = safeMul(safeMul(framesDecoded, outChannels), bytesPerSample);
                ShortBuffer outShort = output.slice().order(ByteOrder.nativeOrder()).asShortBuffer();
                chunk.position(0).limit(safeMul(framesDecoded, inChannels));

                if (inChannels == outChannels) {
                    convertFloatToPcm16Interleaved(chunk, outShort, framesDecoded, outChannels, dither);
                } else if (inChannels == 1 && outChannels == 2) {
                    convertMonoToStereoPcm16(chunk, outShort, framesDecoded, dither);
                } else {
                    float[] downmix = downmixTmp;
                    if (downmix == null) throw new IllegalStateException("Decoder downmix workspace is unavailable");
                    downmixToStereo(chunk, framesDecoded, inChannels, downmix);
                    downmixGain = applyLimiterStereoGain(downmix, framesDecoded, downmixGain);
                    convertFloatArrayToPcm16Interleaved(downmix, outShort, framesDecoded, 2, dither);
                }

                output.position(output.position() + bytesDecoded);
                return bytesDecoded;
            }
        }

        public boolean isEof() {
            synchronized (nativeLock) {
                return !isOpen || eof;
            }
        }

        public void seekStart() {
            synchronized (nativeLock) {
                requireOpen();
                if (!STBVorbis.stb_vorbis_seek_start(handle)) {
                    throw new IllegalStateException("stb_vorbis_seek_start failed");
                }
                eof = false;
            }
        }

        public void seekSeconds(double seconds) {
            synchronized (nativeLock) {
                requireOpen();
                double finiteSeconds = Double.isFinite(seconds) ? seconds : 0.0;
                double clamped = Math.max(0.0, finiteSeconds);
                if (durationSeconds > 0.0) {
                    clamped = Math.min(clamped, Math.max(0.0, durationSeconds - (1.0 / Math.max(1, sampleRate))));
                }
                long sample = Math.round(clamped * sampleRate);
                int targetSample = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, sample));
                if (!STBVorbis.stb_vorbis_seek(handle, targetSample)
                        && !STBVorbis.stb_vorbis_seek_start(handle)) {
                    throw new IllegalStateException("Unable to seek OGG/Vorbis decoder");
                }
                eof = false;
            }
        }

        @Override
        public void close() {
            synchronized (nativeLock) {
                if (!isOpen) return;

                // Invalidate Java-visible state before releasing any native memory.
                // A queued late seek/decode will now fail closed instead of calling
                // STB with a freed pointer (the crash captured in hs_err_pid33004).
                isOpen = false;
                eof = true;
                long closingHandle = handle;
                handle = MemoryUtil.NULL;
                FloatBuffer closingChunk = floatChunk;
                floatChunk = null;
                downmixTmp = null;

                Throwable failure = null;
                try {
                    if (closingHandle != MemoryUtil.NULL) {
                        STBVorbis.stb_vorbis_close(closingHandle);
                    }
                } catch (Throwable t) {
                    failure = t;
                }
                try {
                    MemoryUtil.memFree(oggBuffer);
                } catch (Throwable t) {
                    if (failure == null) failure = t; else failure.addSuppressed(t);
                }
                try {
                    if (closingChunk != null) MemoryUtil.memFree(closingChunk);
                } catch (Throwable t) {
                    if (failure == null) failure = t; else failure.addSuppressed(t);
                }

                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                if (failure != null) throw new RuntimeException("Failed to close OGG/Vorbis decoder", failure);
            }
        }

        private void requireOpen() {
            if (!isOpen || handle == MemoryUtil.NULL) {
                throw new IllegalStateException("Decoder is closed");
            }
        }
    }

    private static int safeMul(int a, int b) {
        long v = (long) a * (long) b;
        if (v < 0L || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer size overflow: " + a + " * " + b);
        }
        return (int) v;
    }

    private static int safeAdd(int a, int b) {
        long value = (long) a + (long) b;
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer size overflow: " + a + " + " + b);
        }
        return (int) value;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new RuntimeException(failure);
    }

    private static int growCapacity(int current, int needed) {
        int cap = Math.max(current, 1);
        while (cap < needed) {
            int next = cap + (cap >> 1); // 1.5x
            cap = (next > cap) ? next : needed;
        }
        return cap;
    }

    private static void downmixToStereo(FloatBuffer in, int frames, int inChannels, float[] outStereoInterleaved) {
        // Heuristics assume Vorbis channel order similar to WAV for common layouts.
        // 1: mono
        // 3: L C R
        // 4: L R Ls Rs
        // 5: L C R Ls Rs
        // 6: L C R Ls Rs LFE
        final float center = 0.70710678f; // 1/sqrt(2)
        final float surround = 0.5f;
        final float lfe = 0.0f; // ignore LFE for safety

        int dstIdx = 0;
        int base = in.position();
        for (int f = 0; f < frames; f++) {
            float L = 0, R = 0;
            if (inChannels == 3) {
                int srcIdx = base + f * inChannels;
                float l = in.get(srcIdx);
                float c = in.get(srcIdx + 1);
                float r = in.get(srcIdx + 2);
                L = l + c * center;
                R = r + c * center;
            } else if (inChannels == 4) {
                int srcIdx = base + f * inChannels;
                float l = in.get(srcIdx);
                float r = in.get(srcIdx + 1);
                float ls = in.get(srcIdx + 2);
                float rs = in.get(srcIdx + 3);
                L = l + ls * surround;
                R = r + rs * surround;
            } else if (inChannels == 5) {
                int srcIdx = base + f * inChannels;
                float l = in.get(srcIdx);
                float c = in.get(srcIdx + 1);
                float r = in.get(srcIdx + 2);
                float ls = in.get(srcIdx + 3);
                float rs = in.get(srcIdx + 4);
                L = l + c * center + ls * surround;
                R = r + c * center + rs * surround;
            } else if (inChannels >= 6) {
                int srcIdx = base + f * inChannels;
                float l = in.get(srcIdx);
                float c = in.get(srcIdx + 1);
                float r = in.get(srcIdx + 2);
                float ls = in.get(srcIdx + 3);
                float rs = in.get(srcIdx + 4);
                float lfeCh = in.get(srcIdx + 5);
                L = l + c * center + ls * surround + lfeCh * lfe;
                R = r + c * center + rs * surround + lfeCh * lfe;
            } else {
                // Fallback: average even/odd channels
                int srcIdx = base + f * inChannels;
                for (int ch = 0; ch < inChannels; ch++) {
                    float v = in.get(srcIdx + ch);
                    if ((ch & 1) == 0) L += v; else R += v;
                }
                float inv = 1.0f / Math.max(1, inChannels / 2);
                L *= inv;
                R *= inv;
            }

            outStereoInterleaved[dstIdx] = L;
            outStereoInterleaved[dstIdx + 1] = R;
            dstIdx += 2;
        }
    }

    private static float applyLimiterStereoGain(float[] stereoInterleaved, int frames, float currentGain) {
        float peak = 0.0f;
        int samples = frames * 2;
        for (int i = 0; i < samples; i++) {
            float a = Math.abs(stereoInterleaved[i]);
            if (a > peak) peak = a;
        }
        if (peak <= 1.0f) {
            if (currentGain != 1.0f) {
                for (int i = 0; i < samples; i++) {
                    stereoInterleaved[i] *= currentGain;
                }
            }
            return currentGain;
        }

        float neededGain = 1.0f / peak;
        float newGain = Math.min(currentGain, neededGain);
        if (newGain == 1.0f) return 1.0f;
        for (int i = 0; i < samples; i++) {
            stereoInterleaved[i] *= newGain;
        }
        return newGain;
    }

    private static void convertMonoToStereoPcm16(FloatBuffer mono, ShortBuffer outStereo, int frames, Dither dither) {
        for (int i = 0; i < frames; i++) {
            float v = mono.get();
            short s = floatToPcm16(v, dither);
            outStereo.put(s);
            outStereo.put(s);
        }
    }

    private static void convertFloatToPcm16Interleaved(FloatBuffer in, ShortBuffer out, int frames, int channels, Dither dither) {
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            out.put(floatToPcm16(in.get(), dither));
        }
    }

    private static void convertFloatArrayToPcm16Interleaved(float[] in, ShortBuffer out, int frames, int channels, Dither dither) {
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            out.put(floatToPcm16(in[i], dither));
        }
    }

    /**
     * Float PCM expected in [-1, 1]. Apply TPDF dithering then clamp and convert.
     * This avoids nasty quantization distortion at low levels.
     */
    private static short floatToPcm16(float v, Dither dither) {
        // TPDF dither amplitude ~= 1 LSB.
        float d = dither.nextTpdf() * (1.0f / PCM16_MAX);
        float x = (v + d) * PCM16_MAX;
        if (x > PCM16_MAX) x = PCM16_MAX;
        if (x < PCM16_MIN) x = PCM16_MIN;
        return (short) Math.round(x);
    }

    private static final class Dither {
        private int state = 0x12345678;

        // Returns uniform float in [0,1)
        private float nextUniform() {
            // xorshift32
            int x = state;
            x ^= (x << 13);
            x ^= (x >>> 17);
            x ^= (x << 5);
            state = x;
            // Convert to [0,1)
            return (x >>> 1) * (1.0f / 2147483648.0f);
        }

        // TPDF in [-1,1)
        float nextTpdf() {
            return (nextUniform() - nextUniform());
        }
    }
}
