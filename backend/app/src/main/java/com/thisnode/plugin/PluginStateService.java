package com.thisnode.plugin;

import com.thisnode.plugin.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件状态与配置的门面。
 *
 * <p><b>全局启停的真相源是 PF4J/SBP</b>，不是数据库：插件 jar 在不在、有没有被管理上停用，
 * 由 PF4J 的插件状态（以及它自己的停用名单 {@code plugins/disabled.txt}）决定。
 * 这里只做转发与通知，不再另存一份标记 —— 两份真相源迟早会打架。
 *
 * <p>插件自己的数据都在 {@link PluginDataStore}（一个文件）里按 entityType 分区：
 * <ul>
 *   <li>{@code block}/{@code note}/… —— per-entity 开关（卡片插件用，键 {@code enabled}）；</li>
 *   <li>{@code config} —— 插件配置项（entityId = pluginId，键 = 配置项 key）。</li>
 * </ul>
 *
 * <p>状态翻转一律在这里通知插件，而不是在 REST 层：任何调用方（控制器、测试、将来的服务）都得到
 * 同一个生命周期——插件恰好在状态落定之后、且只被通知一次。
 */
@Service
public class PluginStateService {

    private static final Logger log = LoggerFactory.getLogger(PluginStateService.class);

    private final PluginDataStore store;
    private final PluginRegistry registry;

    private final org.springframework.beans.factory.ObjectProvider<org.pf4j.PluginManager> pluginManager;
    public PluginStateService(PluginDataStore store, PluginRegistry registry,
                              org.springframework.beans.factory.ObjectProvider<org.pf4j.PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
        this.store = store;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ per-entity

    public boolean isEnabled(String pluginId, String entityType, String entityId) {
        return store.isEnabled(pluginId, entityType, entityId);
    }

    /**
     * Enabled-ness for many entities at once, so a page of cards costs one query instead of one
     * per card. Untouched ids come back {@code false}.
     */
    public Map<String, Boolean> enabledMap(String pluginId, String entityType, List<String> entityIds) {
        return store.enabledMap(pluginId, entityType, entityIds);
    }

    /**
     * 某插件在某类实体上「开着」的全部 id。
     *
     * <p>方向与 {@link #enabledMap} 相反：那个问「这些实体里哪些开着」，这个问「开着的都有谁」——
     * 插件画自己的列表（收藏页之类）用后者。
     */
    public List<String> enabledEntityIds(String pluginId, String entityType) {
        return store.enabledEntityIds(pluginId, entityType);
    }

    public void setEnabled(String pluginId, String entityType, String entityId, boolean enabled) {
        store.setEnabled(pluginId, entityType, entityId, enabled);
        // Fired after the persist, so the hook's own reads observe the new state.
        registry.get(pluginId).onEntityStateChanged(entityType, entityId, enabled);
    }

    // ------------------------------------------------------------------ 全局启停

    /**
     * 插件是否"启用"。
     *
     * <p><b>权威是 PF4J/SBP</b>：插件 jar 在不在、有没有被管理上停用，由它决定。
     * 所以"直接删掉 plugins/ 里的 jar"或"改 PF4J 的停用名单"也会立刻在侧栏与闸门上体现。
     *
     * <p>{@code RESOLVED} 也算启用：插件已安装、容器还没起来那一小段（启动过程的「激活」阶段）
     * 不该被当成停用。未装载的 id 一律返回 false —— 闸门对未知插件另有判断，不会把它当状态错误。
     */
    public boolean isActive(String pluginId) {
        org.pf4j.PluginManager manager = pluginManager.getIfAvailable();
        org.pf4j.PluginWrapper wrapper = manager == null ? null : manager.getPlugin(pluginId);
        if (wrapper == null) {
            return false;
        }
        org.pf4j.PluginState state = wrapper.getPluginState();
        // STARTED 在服务；RESOLVED 已安装但还没被启动（启动过程阶段 4 会走到这里）；
        // DISABLED 是管理上停用；STOPPED/FAILED 表示没在服务。
        return state == org.pf4j.PluginState.STARTED || state == org.pf4j.PluginState.RESOLVED;
    }

    /**
     * 启用/停用插件：落到 PF4J 的 enable/disable（它自己持久化管理状态）并显式 start/stop，
     * 让它在本次运行里立即生效；随后才通知插件本体。
     *
     * @throws com.thisnode.plugin.api.ConflictException 拒绝停用核心插件（notes）——
     *         它承载整个应用的笔记领域，停用等于把主体下线；管理页对它不展示停用按钮，
     *         这里是最后一道闸。
     */
    public void setActive(String pluginId, boolean active) {
        if (!active) {
            Plugin plugin = registry.get(pluginId);
            if (plugin != null && plugin.descriptor().undeactivatable()) {
                throw new com.thisnode.plugin.api.ConflictException(
                        "「" + plugin.descriptor().title() + "」承载主体业务，不允许停用");
            }
        }
        org.pf4j.PluginManager manager = pluginManager.getIfAvailable();
        if (manager != null && manager.getPlugin(pluginId) != null) {
            if (active) {
                manager.enablePlugin(pluginId);
                manager.startPlugin(pluginId);
            } else {
                manager.stopPlugin(pluginId);
                manager.disablePlugin(pluginId);
            }
        }
        notifyGlobalState(pluginId, active);
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 插件的配置原始值（不脱敏）；插件自己读配置用这个。
     */
    public Map<String, String> configValues(String pluginId) {
        return store.values(pluginId, PluginDataStore.ENTITY_CONFIG, pluginId);
    }

    /**
     * 单项配置，缺失或空白时给默认值。
     */
    public String configValue(String pluginId, String key, String fallback) {
        String value = store.value(pluginId, PluginDataStore.ENTITY_CONFIG, pluginId, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 写一项配置；{@code null} 表示删除该项（回到 schema 默认值）。
     */
    public void setConfigValue(String pluginId, String key, String value) {
        if (value == null) {
            store.remove(pluginId, PluginDataStore.ENTITY_CONFIG, pluginId, key);
        } else {
            store.put(pluginId, PluginDataStore.ENTITY_CONFIG, pluginId, key, value);
        }
    }

    /**
     * 给管理页看的配置视图：按插件的 schema 组织。
     *
     * <p>敏感项只回 {@code hasValue}，<b>永不回传内容</b>——SecretKey 这类东西存进来就不该再出得去；
     * 提交时留空表示「不改动」。
     */
    public List<Map<String, Object>> configView(Plugin plugin) {
        String pluginId = plugin.descriptor().id();
        Map<String, String> stored = configValues(pluginId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PluginConfigField field : plugin.configSchema()) {
            String value = stored.get(field.key());
            boolean hasValue = value != null && !value.isBlank();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", field.key());
            item.put("label", field.label());
            item.put("type", field.type().name().toLowerCase(java.util.Locale.ROOT));
            item.put("required", field.required());
            item.put("secret", field.secret());
            item.put("help", field.help());
            item.put("defaultValue", field.defaultValue());
            item.put("hasValue", hasValue);
            item.put("value", field.secret() ? null : (hasValue ? value : field.defaultValue()));
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ 重载

    /**
     * 重载一个插件：SBP/PF4J 会**重建插件的类加载器**并重新启动它的 Spring 子容器，
     * 所以"把磁盘上的 jar 换成新版本"可以不重启应用就生效（这也是 PF4J 的核心能力之一）。
     *
     * <p>配套的一件事：重载后**补一次全局状态通知** —— 新实例没经历过启停回调，不通知就等于不知道
     * 自己在服务。
     */
    public void reload(String pluginId) {
        if (registry.isRegistered(pluginId)) {
            Plugin target = registry.get(pluginId);
            if (target.descriptor().undeactivatable()) {
                // 不可停用的插件是全部业务端点的宿主：重载会重建它的 Spring 子容器，期间路由与索引短暂缺失。
                // （早先的理由是"兄弟插件共享它导入的门面"，那层共享已经不存在了。）拒绝并提示重启。
                throw new com.thisnode.plugin.api.ConflictException(
                        "「" + target.descriptor().title() + "」承载主体业务，不支持在线重载（它承载全部业务端点）；更换 notes 插件后请重启应用生效");
            }
        }
        org.pf4j.PluginManager manager = pluginManager.getIfAvailable();
        if (manager instanceof org.laxture.sbp.SpringBootPluginManager sbp) {
            org.pf4j.PluginState state = sbp.reloadPlugins(pluginId);
            if (state == null) {
                // SBP 在"重新装载这个插件失败"时返回 null（例如新 jar 的描述符坏掉）。
                // 抛出去让 API 给出 409 与原因，而不是假装成功。
                throw new IllegalStateException("重载插件「" + pluginId + "」失败：新的插件包无法装载，"
                        + "请检查它的 plugin.properties 与 plugins/ 目录（旧版本可能已被卸载）");
            }
            log.info("插件 {} 已重载，状态 {}", pluginId, state);
        } else {
            // 没有 SBP 管理器就没有"重载类加载器"这回事，退化成 stop + start（换 jar 不生效）。
            setActive(pluginId, false);
            setActive(pluginId, true);
        }
        notifyGlobalState(pluginId, isActive(pluginId));
    }

    /**
     * 磁盘上那个 jar 声明的版本；读不到（例如开发模式的目录形态）返回 null。
     *
     * <p>与 {@code version}（当前**运行中**的实例版本）对比，就能看出"有没有换了 jar 还没重载"。
     */
    private String diskVersion(String pluginId) {
        org.pf4j.PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return null;
        }
        org.pf4j.PluginWrapper wrapper = manager.getPlugin(pluginId);
        // 描述符查找器在 PF4J 里是 protected，SBP 的管理器把它放开了 —— 这里用 SBP 的类型。
        if (wrapper == null || wrapper.getPluginPath() == null
                || !(manager instanceof org.laxture.sbp.SpringBootPluginManager sbp)) {
            return null;
        }
        try {
            org.pf4j.PluginDescriptor descriptor = sbp.getPluginDescriptorFinder().find(wrapper.getPluginPath());
            return descriptor == null ? null : descriptor.getVersion();
        } catch (RuntimeException e) {
            return null;
        }
    }


    // ------------------------------------------------------------------ 通知插件本体

    /**
     * 问一个插件的健康。
     *
     * <p><b>不能在停用的外置插件上直接问</b>：它的 Spring 子容器已经关闭，而 {@code health()}
     * 通常要读自己的 bean，于是抛 {@code IllegalStateException: … has been closed already}，
     * 进了全局异常处理器就变成 <b>409</b> —— 一个插件停用会把整个插件列表（每项都要 describe）
     * 一起打挂，调用方连"它现在是停用的"都读不到。所以：没在服务的插件给静态结论，
     * 真抛异常也降级成 unknown；管理面永远读得出来。
     */
    public PluginHealth healthOf(Plugin plugin) {
        String pluginId = plugin.descriptor().id();
        if (isExternal(pluginId) && !isActive(pluginId)) {
            return PluginHealth.unknown("插件已停用，容器未运行");
        }
        try {
            return plugin.health();
        } catch (RuntimeException e) {
            log.warn("插件 {} 的健康检查抛异常：{}", pluginId, e.toString());
            return PluginHealth.unknown("健康检查失败：" + e.getMessage());
        }
    }

    /**
     * 全局启停变化后通知插件本体（<b>尽力而为</b>）。
     *
     * <p>停用外置插件时容器已经关掉，再往里通知只会抛"容器已关闭"；而插件自己的
     * {@code stop()} 生命周期已经通知过它了，所以这里直接跳过，不让管理请求失败。
     * 启动时同理：管理上停用的插件根本没起容器，不该被通知（否则启动就炸）。
     */
    public void notifyGlobalState(String pluginId, boolean active) {        if (!active && isExternal(pluginId)) {
            return;
        }
        try {
            registry.get(pluginId).onGlobalStateChanged(active);
        } catch (RuntimeException e) {
            log.warn("通知插件 {} 全局状态={} 失败：{}", pluginId, active, e.toString());
        }
    }

    /**
     * 配置变更后的通知，同样是尽力而为：停用的外置插件容器已关，跳过即可
     * （值已经落盘，插件下次启动会读到）。
     */
    public void notifyConfigChanged(Plugin plugin) {
        String pluginId = plugin.descriptor().id();
        if (isExternal(pluginId) && !isActive(pluginId)) {
            return;
        }
        try {
            plugin.onConfigChanged();
        } catch (RuntimeException e) {
            log.warn("通知插件 {} 配置已变更失败：{}", pluginId, e.toString());
        }
    }

    /** 该 id 是不是 PF4J/SBP 装载的外置插件（内置插件没有自己的类加载器）。 */
    private boolean isExternal(String pluginId) {
        return registry.pluginClassLoader(pluginId) != null;
    }

    // ------------------------------------------------------------------ 管理视图

    /**
     * 管理页要的所有东西：描述 + 全局启停 + 健康 + 配置视图 + 磁盘版本。
     */
    public Map<String, Object> describe(Plugin plugin) {
        PluginDescriptor descriptor = plugin.descriptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", descriptor.id());
        out.put("title", descriptor.title());
        out.put("description", descriptor.description());
        out.put("undeactivatable", descriptor.undeactivatable());
        out.put("version", descriptor.version());
        out.put("apiVersion", descriptor.apiVersion());
        out.put("active", isActive(descriptor.id()));
        PluginHealth health = healthOf(plugin);
        out.put("health", Map.of("status", health.status(), "message", health.message()));
        out.put("configSchema", configView(plugin));
        // 磁盘上那个 jar 的版本：与 version（正在跑的实例）不同就说明"换了 jar，还没重载"。
        String diskVersion = diskVersion(descriptor.id());
        out.put("diskVersion", diskVersion);
        out.put("reloadAvailable", diskVersion != null && !diskVersion.equals(descriptor.version()));
        return out;
    }
}
