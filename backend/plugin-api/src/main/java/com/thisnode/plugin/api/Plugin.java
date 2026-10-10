package com.thisnode.plugin.api;

import java.util.List;

/**
 * A thisnode plugin.
 *
 * <p><b>落地形态：</b>插件是<b>独立构件</b>（各自的 Maven 模块），只依赖 {@code plugin-api}。
 * 因此它编译期就看不到核心的 {@code NoteService} / {@code NoteRepository} / {@code api.dto} ——
 * 想存自己的状态与配置，只有 {@link PluginDataStore}；插件之间没有任何共享契约
 * （每个插件只读写自己的文件），核心插件缺位也不会挡住兄弟插件启动。
 * 这条边界由 Maven 依赖而不是文档保证：写错就编译不过。
 *
 * <p>实现类加 {@code @Component} 即被发现（核心的注册表收集容器里所有本接口的 bean），
 * 随后自动获得：管理页条目（描述/版本/健康/配置）、全局启停与端点闸门、配置存储与脱敏。
 */
public interface Plugin {

    /**
     * 当前插件契约版本。插件声明自己按哪个版本编译（见 {@link PluginDescriptor#apiVersion()}），
     * 核心只接受它支持的版本——不匹配时启动即失败，而不是运行到一半才出诡异行为。
     *
     * <p>改契约（加方法、改语义、动 {@link PluginDataStore} 的键格式）时递增这个数字。
     *
     * <p>v2：删掉自带的 SQL 迁移序列（{@code migrationLocation()}）—— 数据库层已移除，迁移没有
     * 可执行的对象。按 v1 编译的旧 jar 会被注册表跳过并告警，而不是带着一个空转的方法继续跑。
     */
    int API_VERSION = 3;

    PluginDescriptor descriptor();

    /**
     * 全局启停。{@code false} 之后底座会拦下该插件自己端点的请求（管理面除外）。
     *
     * <p>卡片插件可以忽略它——卡片级开关走 {@link #onEntityStateChanged}；服务插件通常在这里
     * 停掉自己的后台动作。
     */
    default void onGlobalStateChanged(boolean enabled) {
    }

    /**
     * Called when one of the plugin's per-entity states flips.
     *
     * <p>This is the plugin's chance to initialize or release its own data: the memory-curve
     * plugin, for example, creates a scheduling row (due immediately) when a card enables it, and
     * keeps the row (history) when the card disables it.
     *
     * @param entityType the entity kind, e.g. {@code "block"}
     * @param entityId   the entity id
     * @param enabled    the new state
     */
    default void onEntityStateChanged(String entityType, String entityId, boolean enabled) {
    }

    /** 这个插件需要哪些配置项；默认没有。 */
    default List<PluginConfigField> configSchema() {
        return List.of();
    }

    /** 配置被改动之后的通知（例如清掉缓存的凭据）。 */
    default void onConfigChanged() {
    }

    /** 自我体检；默认「没什么可说的」。 */
    default PluginHealth health() {
        return PluginHealth.ok("运行正常");
    }
}
