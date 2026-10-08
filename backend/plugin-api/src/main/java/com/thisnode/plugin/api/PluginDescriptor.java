package com.thisnode.plugin.api;

/**
 * What the plugin surface shows about a plugin.
 *
 * @param id          稳定标识：用在 URL 里，也是 {@code PluginDataStore} 里状态与配置的分区键
 * @param title       显示名
 * @param description 一句话说明
 * @param kind        卡片插件还是服务插件（决定它有哪些能力槽，见 {@link PluginKind}）
 * @param version     插件自己的版本号，用于排查与将来的升级
 * @param apiVersion  编译时所用的插件契约版本（见 {@link Plugin#API_VERSION}）；不匹配核心会拒绝启动
 */
public record PluginDescriptor(String id, String title, String description,
                               PluginKind kind, String version, int apiVersion) {

    public static PluginDescriptor card(String id, String title, String description, String version) {
        return new PluginDescriptor(id, title, description, PluginKind.CARD, version, Plugin.API_VERSION);
    }

    /** 实体插件：给某类实体（笔记/卡片/…）加 per-entity 状态与工具。 */
    public static PluginDescriptor entity(String id, String title, String description, String version) {
        return new PluginDescriptor(id, title, description, PluginKind.ENTITY, version, Plugin.API_VERSION);
    }

    public static PluginDescriptor service(String id, String title, String description, String version) {
        return new PluginDescriptor(id, title, description, PluginKind.SERVICE, version, Plugin.API_VERSION);
    }
}
