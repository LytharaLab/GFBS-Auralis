package org.mirage.gfbs.auralis.core;

import org.mirage.gfbs.auralis.api.event.AuralisEventBus;
import org.mirage.gfbs.auralis.api.plugin.AuralisPlugin;
import org.mirage.gfbs.auralis.api.plugin.PluginContext;
import org.mirage.gfbs.auralis.api.processing.AudioProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件管理器实现
 * <p>
 * 负责加载插件、管理全局处理器和事件总线。
 * </p>
 */
public class AuralisPluginManager implements PluginContext {
    private final AuralisEventBus eventBus;
    private final List<AudioProcessor> globalProcessors = new CopyOnWriteArrayList<>();
    private final List<AuralisPlugin> loadedPlugins = new CopyOnWriteArrayList<>();

    public AuralisPluginManager() {
        this.eventBus = new AuralisEventBusImpl();
    }

    /**
     * 加载并启用一个插件
     *
     * @param plugin 要加载的插件实例
     */
    public void loadPlugin(AuralisPlugin plugin) {
        try {
            System.out.println("[Auralis] Loading plugin: " + plugin.getName() + " v" + plugin.getVersion());
            plugin.onEnable(this);
            loadedPlugins.add(plugin);
        } catch (Exception e) {
            System.err.println("[Auralis] Failed to load plugin " + plugin.getName());
            e.printStackTrace();
        }
    }

    /**
     * 禁用并卸载所有插件
     */
    public void shutdown() {
        for (AuralisPlugin plugin : loadedPlugins) {
            try {
                plugin.onDisable();
            } catch (Exception e) {
                System.err.println("[Auralis] Error disabling plugin " + plugin.getName());
                e.printStackTrace();
            }
        }
        loadedPlugins.clear();
        globalProcessors.clear();
    }

    @Override
    public void registerGlobalProcessor(AudioProcessor processor) {
        if (!globalProcessors.contains(processor)) {
            globalProcessors.add(processor);
            // 按照优先级排序，优先级数值越小越靠前
            globalProcessors.sort((p1, p2) -> Integer.compare(p1.getPriority(), p2.getPriority()));
            System.out.println("[Auralis] Registered global processor: " + processor.getId());
        }
    }

    @Override
    public void unregisterGlobalProcessor(AudioProcessor processor) {
        globalProcessors.remove(processor);
    }

    @Override
    public AuralisEventBus getEventBus() {
        return eventBus;
    }

    /**
     * 获取当前的全局处理器列表（只读视图）
     *
     * @return 全局处理器列表
     */
    public List<AudioProcessor> getGlobalProcessors() {
        return Collections.unmodifiableList(globalProcessors);
    }
}
