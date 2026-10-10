package com.thisnode.plugin.api;

/**
 * What the plugin surface shows about a plugin.
 *
 * <p>这里只有**有行为的**字段：{@code undeactivatable} 是唯一带语义的标记
 * （管理面拒绝停用/重载、端点闸门总是放行）。插件的「分类标签」（卡片/实体/服务…）
 * **不在这里** —— 一个插件实际贡献什么，由它在界面侧调用哪些 {@code register*} 决定，
 * 声明式的分类只会与事实漂移。
 *
 * @param id               稳定标识：用在 URL 里，也是 {@code PluginDataStore} 里状态与配置的分区键
 * @param title            显示名
 * @param description      一句话说明
 * @param undeactivatable  不可停用/不可在线重载（承载主体业务领域的插件，如 notes）
 * @param version          插件自己的版本号，用于排查与将来的升级
 * @param apiVersion       编译时所用的插件契约版本（见 {@link Plugin#API_VERSION}）；不匹配核心会拒绝启动
 */
public record PluginDescriptor(String id, String title, String description,
                               boolean undeactivatable, String version, int apiVersion) {

    /** 普通插件：可停用、可在线重载。 */
    public static PluginDescriptor of(String id, String title, String description, String version) {
        return new PluginDescriptor(id, title, description, false, version, Plugin.API_VERSION);
    }

    /**
     * 核心插件：承载主体原有的业务领域（参考 notes）。
     *
     * <p>不允许停用/重载 —— 理由是整块业务能力会随一次误点整体下线
     * （不是「别的插件依赖它」：兄弟插件之间没有依赖）。
     */
    public static PluginDescriptor core(String id, String title, String description, String version) {
        return new PluginDescriptor(id, title, description, true, version, Plugin.API_VERSION);
    }
}
