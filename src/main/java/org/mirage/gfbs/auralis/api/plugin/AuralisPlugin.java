package org.mirage.gfbs.auralis.api.plugin;

/**
 * 插件生命周期接口
 * <p>
 * 所有的插件必须实现此接口。
 * </p>
 */
public interface AuralisPlugin {
    /**
     * 当插件被加载并初始化时调用。
     * 插件应在此处进行初始化操作，例如注册事件监听器、加载资源等。
     *
     * @param context 插件上下文，提供访问 API 和服务的入口。
     */
    void onEnable(PluginContext context);

    /**
     * 当插件被卸载或系统关闭时调用。
     * 插件应在此处释放资源、注销监听器等。
     */
    void onDisable();

    /**
     * 获取插件的唯一名称
     *
     * @return 插件名称，建议使用小写字母和下划线
     */
    String getName();

    /**
     * 获取插件版本号
     *
     * @return 插件版本号
     */
    String getVersion();
}
