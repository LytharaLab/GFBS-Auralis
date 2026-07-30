package org.lytharalab.gfbs.auralis.api.plugin;

import org.lytharalab.gfbs.auralis.api.event.AuralisEventBus;
import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;

/**
 * 插件上下文
 * <p>
 * 提供插件与 Auralis 引擎交互的入口点。
 * 插件通过此接口注册音频处理器、监听事件或获取其他服务。
 * </p>
 */
public interface PluginContext {
    /**
     * 注册一个全局音频处理器
     * <p>
     * 全局处理器将应用于所有新创建的声音实例。
     * </p>
     *
     * @param processor 要注册的音频处理器
     */
    void registerGlobalProcessor(AudioProcessor processor);

    /**
     * 注销一个全局音频处理器
     *
     * @param processor 要注销的音频处理器
     */
    void unregisterGlobalProcessor(AudioProcessor processor);

    /**
     * 获取事件总线
     * <p>
     * 用于监听引擎生命周期事件或其他插件发布的事件。
     * </p>
     *
     * @return 事件总线实例
     */
    AuralisEventBus getEventBus();
    
    // TODO: 添加更多功能，例如资源加载、日志记录等
}
