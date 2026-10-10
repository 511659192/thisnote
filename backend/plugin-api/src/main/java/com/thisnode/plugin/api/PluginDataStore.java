package com.thisnode.plugin.api;

import java.util.List;
import java.util.Map;

/**
 * 插件数据的读写面（**契约**）：一个插件记「哪些实体开着」。
 *
 * <p><b>为什么单独一层：</b>插件要读自己的状态，但平台的状态服务依赖插件注册表
 * （要在状态翻转时触发钩子），而注册表装着所有插件——插件直接注入状态服务就会构成 Spring 装不出来的环。
 * 所以把「读写」与「翻转时通知谁」拆开：插件只依赖本接口，状态服务在此之上加钩子。
 *
 * <p><b>实现由平台提供</b>（本接口在契约模块里，实现放在平台侧）：底层是**一个 JSON 文件**
 * （{@code <data-root>/plugin-data.json}），只有两级——插件 id 与实体 id，值就是开关本身：
 * <pre>{@code
 * { "favorite": { "<noteId>": true }, "memory-curve": { "<cardId>": true } }
 * }</pre>
 *
 * <p>平台**不解释实体是什么**：没有 entityType 分区、没有键名（早先那两层已删掉）——平台只知道
 * 「某个插件记着若干个实体 id」，至于那是笔记还是卡片、这个开关叫不叫 enabled，全是插件的语义。
 * 关掉的实体不进文件，所以文件里只留「开着」的。
 *
 * <p><b>没有内存缓存</b>：每次调用现读文件（体量小），手改 {@code plugin-data.json} 下一次请求即生效，
 * 也不存在"缓存与文件谁更新"的问题。
 *
 * <p>实例由主应用创建；插件经 SBP 的 {@code importBean} 拿到主容器的那个实现。
 */
public interface PluginDataStore {

    /** 单个实体开着吗。 */
    boolean isEnabled(String pluginId, String entityId);

    /** 开/关一个实体；关掉就是从文件里删掉（文件里只留开着的）。 */
    void setEnabled(String pluginId, String entityId, boolean enabled);

    /** 批量问「这些实体里哪些开着」；没记录的一律 {@code false}。 */
    Map<String, Boolean> enabledMap(String pluginId, List<String> entityIds);

    /**
     * 反向问「开着的都有谁」（不限定候选集）。
     *
     * <p>与 {@link #enabledMap} 的方向相反：那个是「这些实体里哪些开着」，这个是「开着的都有谁」——
     * 插件画自己的列表（例如收藏页）时需要的正是后者。
     */
    List<String> enabledEntityIds(String pluginId);
}
