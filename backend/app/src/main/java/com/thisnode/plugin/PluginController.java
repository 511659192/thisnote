package com.thisnode.plugin;

import com.thisnode.plugin.api.Plugin;
import com.thisnode.plugin.api.PluginConfigField;
import com.thisnode.plugin.api.PluginDataStore;
import com.thisnode.plugin.api.PluginDescriptor;
import com.thisnode.plugin.api.PluginHealth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件管理面：列表、全局启停、配置、健康，以及通用的 per-entity 状态。
 *
 * <p>路径约定：<b>这里出现的都是管理动作</b>（任何插件都长这样），插件自己的业务端点放在
 * 同样前缀下的其他子路径（如 {@code /api/plugins/baidu-pan/push}）——{@link PluginEndpointGate}
 * 靠这份白名单区分「管理面」与「插件自有端点」，停用插件时只拦后者。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    /** 管理面用到的子路径；插件自有端点不得占用这些名字。 */
    static final List<String> MANAGEMENT_SEGMENTS =
            List.of("states", "entities", "config", "health", "active", "reload");

    private final PluginRegistry registry;
    private final PluginStateService states;

    public PluginController(PluginRegistry registry, PluginStateService states) {
        this.registry = registry;
        this.states = states;
    }

    /** 全部插件的管理视图：描述 + 全局启停 + 健康 + 配置 schema。 */
    @GetMapping
    public List<Map<String, Object>> plugins() {
        return registry.all().stream().map(states::describe).toList();
    }

    @GetMapping("/{pluginId}")
    public Map<String, Object> plugin(@PathVariable String pluginId) {
        return states.describe(registry.get(pluginId));
    }

    /** 全局启停。停用后该插件的自有端点会被拦下（管理面仍可访问，否则没法再打开）。 */
    @PutMapping("/{pluginId}/active")
    public Map<String, Object> setActive(@PathVariable String pluginId,
                                         @RequestParam boolean value) {
        states.setActive(pluginId, value);
        return states.describe(registry.get(pluginId));
    }

    /**
     * 重载：换掉 {@code plugins/} 里的 jar 之后，不需要重启应用就能让新版本生效
     * （SBP 会重建插件的类加载器与 Spring 子容器，随后补跑该插件的新迁移）。
     *
     * <p>放在这里而不是直接开放 SBP 自带的 {@code /sbp/reload/*}：管理面只留一个入口，
     * 于是"停用的插件端点被拦下"这条闸门规则对重载同样成立（{@code reload} 已进白名单）。
     * 官方的 {@code /sbp/**} 仍然可用，供运维/脚本使用，见 application.yml 的注释。
     */
    @PostMapping("/{pluginId}/reload")
    public Map<String, Object> reload(@PathVariable String pluginId) {
        registry.get(pluginId);
        states.reload(pluginId);
        return states.describe(registry.get(pluginId));
    }

    @GetMapping("/{pluginId}/config")
    public List<Map<String, Object>> config(@PathVariable String pluginId) {
        return states.configView(registry.get(pluginId));
    }

    /**
     * 写配置。
     *
     * <p>语义刻意做成「只改你给的键」：请求里没出现的键保持原值；敏感项传空串表示<b>不改动</b>
     * （因为读取时它本来就是空的，若把空串当清空，用户每保存一次就会把密钥擦掉）。
     */
    @PutMapping("/{pluginId}/config")
    public Map<String, Object> updateConfig(@PathVariable String pluginId,
                                            @RequestBody Map<String, String> values) {
        Plugin plugin = registry.get(pluginId);
        for (PluginConfigField field : plugin.configSchema()) {
            if (!values.containsKey(field.key())) {
                continue;
            }
            String value = values.get(field.key());
            boolean blank = value == null || value.isBlank();
            if (blank && field.secret()) {
                continue; // 留空 = 不改动
            }
            states.setConfigValue(pluginId, field.key(), blank ? null : value);
        }
        states.notifyConfigChanged(plugin);
        return states.describe(plugin);
    }

    @GetMapping("/{pluginId}/health")
    public PluginHealth health(@PathVariable String pluginId) {
        return states.healthOf(registry.get(pluginId));
    }

    // ------------------------------------------------------------------ per-entity 状态

    /** Enabled-ness for a batch of entities, so a page of cards costs one request. */
    @GetMapping("/{pluginId}/states")
    public Map<String, Boolean> states(@PathVariable String pluginId,
                                       @RequestParam String entityType,
                                       @RequestParam List<String> ids) {
        registry.get(pluginId);
        return states.enabledMap(pluginId, entityType, ids);
    }

    /**
     * 「开着这个插件的实体都有谁」——方向与 /states 相反。
     *
     * <p>插件画自己的列表时用（收藏页要知道收藏了哪些笔记）：状态只存 id，
     * 具体内容由插件通过门面回核心取。
     */
    @GetMapping("/{pluginId}/entities")
    public List<String> entities(@PathVariable String pluginId,
                                 @RequestParam String entityType) {
        registry.get(pluginId);
        return states.enabledEntityIds(pluginId, entityType);
    }

    /**
     * 单个实体的开关状态。
     *
     * <p>与上面两个「批量」面互补：这里问一个，{@code /states} 问一批。
     */
    @GetMapping("/{pluginId}/states/{entityType}/{entityId}")
    public Map<String, Object> state(@PathVariable String pluginId,
                                     @PathVariable String entityType,
                                     @PathVariable String entityId) {
        registry.get(pluginId);
        return Map.of("enabled", states.isEnabled(pluginId, entityType, entityId), "active",
                states.isActive(pluginId));
    }

    /**
     * 翻转一个实体的开关（卡片上的 🧠、笔记上的 ★ 都走这里）。
     *
     * <p>钩子在状态服务内部触发，所以「落盘」与「通知插件」不会各自漂移；这里只校验插件存在。
     */
    @PutMapping("/{pluginId}/states/{entityType}/{entityId}")
    public Map<String, Object> setState(@PathVariable String pluginId,
                                        @PathVariable String entityType,
                                        @PathVariable String entityId,
                                        @RequestParam boolean value) {
        registry.get(pluginId);
        states.setEnabled(pluginId, entityType, entityId, value);
        return Map.of("enabled", value);
    }
}
