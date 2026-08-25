package org.lytharalab.gfbs.auralis.api.processing;

import java.nio.ByteBuffer;

/**
 * 音频处理器接口
 * <p>
 * 允许插件在音频数据送入 OpenAL 缓冲区之前拦截并修改 PCM 数据。
 * 典型的应用包括：均衡器 (EQ)、混响、变声器、动态音量调整等。
 * </p>
 */
public interface AudioProcessor extends AutoCloseable {
    /**
     * 处理音频数据
     * <p>
     * 注意：此方法通常在音频 IO 线程或渲染线程中调用，必须保持极高的执行效率。
     * 避免在此方法中进行文件 IO、网络请求或复杂的同步锁操作。
     * </p>
     *
     * @param pcmData  包含原始 PCM 数据的缓冲区 (Direct Buffer)。
     *                 你可以直接修改其中的字节内容。
     *                 注意：不要修改 buffer 的 limit，除非你非常清楚自己在做什么（例如重采样）。
     * @param channels 通道数 (1=单声道 Mono, 2=立体声 Stereo)
     * @param sampleRate 采样率 (例如 44100 Hz)
     * @param bytesRead 本次读取并填充到 buffer 中的有效字节数。
     *                  你应该只处理 buffer 中从 position 到 position + bytesRead 范围的数据。
     * @return 处理后的有效字节数。通常应该返回与 bytesRead 相同的值，除非你进行了改变数据长度的操作。
     */
    int process(ByteBuffer pcmData, int channels, int sampleRate, int bytesRead);

    /**
     * 获取处理器的唯一标识符
     * <p>
     * 建议使用类似 "modid:processor_name" 的格式。
     * </p>
     *
     * @return 唯一标识符
     */
    String getId();
    
    /**
     * 是否启用此处理器
     * <p>
     * 可以在运行时动态开启或关闭处理效果。
     * </p>
     *
     * @return 如果启用则返回 true
     */
    default boolean isEnabled() { return true; }
    
    /**
     * 获取处理器的优先级
     * <p>
     * 数值越小，优先级越高，越先执行。
     * 默认为 0。
     * </p>
     * 
     * @return 优先级数值
     */
    default int getPriority() { return 0; }

    /** Increment when parameters change and static buffers must be rebuilt. */
    default long getRevision() { return 0L; }

    /** Reset delay lines/envelopes before a decoder seek or replay. */
    default void reset() { }

    /** Release processor-owned native/direct resources. */
    @Override
    default void close() { }
}
