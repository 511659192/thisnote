package com.thisnode.plugin.api;

import java.util.List;
import java.util.Map;

/**
 * 插件状态与配置的读写面（**契约**）。
 *
 * <p><b>为什么单独一层：</b>插件要读自己的状态与配置，但平台的状态服务依赖插件注册表
 * （要在状态翻转时触发钩子），而注册表装着所有插件——插件直接注入状态服务就会构成 Spring 装不出来的环。
 * 所以把「读写 KV」和「翻转时通知谁」拆开：插件只依赖本接口，状态服务在此之上加钩子。
 *
 * <p><b>实现由平台提供</b>（本接口在契约模块里，实现放在平台侧）：底层是**一个 JSON 文件**
 * （{@code <data-root>/plugin-data.json}），按
 * {@code pluginId → entityType → entityId → key → value} 分层，便于手工查看与修改。
 * 契约层不引 Jackson 之类的实现技术栈 —— 插件只看到这组方法。
 *
 * <p><b>没有内存缓存</b>：每次调用现读文件（体量小），手改 {@code plugin-data.json} 下一次请求即生效，
 * 也不存在"缓存与文件谁更新"的问题。写入是「读-改-写」+ 原子替换。
 *
 * <p>实例由主应用创建；插件经 SBP 的 {@code importBean} 拿到主容器的那个实现。
 */
public interface PluginDataStore {

    /** 底座自己管理的键：全局启停与 per-entity 开关都用它。 */
    String ENABLED_KEY = "enabled";

    /** entityType：插件级状态（entityId = pluginId）。 */
    String ENTITY_PLUGIN = "plugin";

    /** entityType：插件配置（entityId = pluginId，key = 配置项）。 */
    String ENTITY_CONFIG = "config";

    /** 单个键的值；没有返回 {@code null}。 */
    String value(String pluginId, String entityType, String entityId, String key);

    /** 某实体下的全部键值（一次取整份）。 */
    Map<String, String> values(String pluginId, String entityType, String entityId);

    void put(String pluginId, String entityType, String entityId, String key, String value);

    void remove(String pluginId, String entityType, String entityId, String key);

    boolean isEnabled(String pluginId, String entityType, String entityId);

    void setEnabled(String pluginId, String entityType, String entityId, boolean enabled);

    /** 批量查询「这些实体里哪些开着」。 */
    Map<String, Boolean> enabledMap(String pluginId, String entityType, List<String> entityIds);

    /**
     * 某插件在某类实体上「开着」的全部 id（不限定候选集）。
     *
     * <p>与 {@link #enabledMap} 的方向相反：那个是「这些实体里哪些开着」，这个是「开着的都有谁」——
     * 插件画自己的列表（例如收藏页）时需要的正是后者。
     */
    List<String> enabledEntityIds(String pluginId, String entityType);
}
