package org.mirage.gfbs.auralis.api.event;

import java.util.function.Consumer;

/**
 * Auralis 事件总线
 * <p>
 * 用于插件之间或插件与核心引擎之间的事件通信。
 * 插件可以通过 {@link PluginContext#getEventBus()} 获取此对象。
 * </p>
 */
public interface AuralisEventBus {
    /**
     * 注册事件监听器
     *
     * @param <T> 事件类型，必须实现 {@link AuralisEvent}
     * @param eventClass 要监听的事件类
     * @param handler 事件处理逻辑
     */
    <T extends AuralisEvent> void register(Class<T> eventClass, Consumer<T> handler);

    /**
     * 注销事件监听器
     *
     * @param <T> 事件类型
     * @param eventClass 要注销监听的事件类
     * @param handler 之前注册的事件处理逻辑
     */
    <T extends AuralisEvent> void unregister(Class<T> eventClass, Consumer<T> handler);

    /**
     * 发布一个事件
     * <p>
     * 所有的监听器将同步接收到此事件。
     * </p>
     *
     * @param event 要发布的事件对象
     */
    void post(AuralisEvent event);
}
