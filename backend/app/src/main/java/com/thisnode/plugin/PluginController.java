package com.thisnode.plugin;

import com.thisnode.plugin.api.PluginHealth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件管理面：列表、全局启停与健康；各插件的业务状态由各自端点负责。
 *
 * <p>URL 约定：<b>非 RESTful</b> —— 纯查询使用 GET，写操作使用带动作路径的 POST，没有任何路径
 * 变量，标识一律走 {@code @RequestParam}（插件 id 用 {@code pluginId}）。
 * 插件自己的业务端点放在 {@code /api/plugins/<id>/...} 静态命名空间下
 * —— {@link PluginEndpointHandlerInterceptor} 靠注册表与这份白名单区分「管理面」与「插件自有端点」，
 * 停用插件时只拦后者。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    /** 管理面用到的子路径；插件自有端点不得占用这些名字。 */
    static final List<String> MANAGEMENT_SEGMENTS =
            List.of("get", "get-detail", "get-health", "active", "reload");

    private final PluginRegistry registry;
    private final PluginStateService states;

    public PluginController(PluginRegistry registry, PluginStateService states) {
        this.registry = registry;
        this.states = states;
    }

    /** 全部插件的管理视图：描述 + 全局启停 + 健康。 */
    @GetMapping("/get")
    public List<Map<String, Object>> plugins() {
        return registry.ids().stream().map(states::describe).toList();
    }

    @GetMapping("/get-detail")
    public Map<String, Object> plugin(@RequestParam String pluginId) {
        registry.get(pluginId); // 未知 id → 404（与旧行为一致）
        return states.describe(pluginId);
    }

    /** 全局启停。停用后该插件的自有端点会被拦下（管理面仍可访问，否则没法再打开）。 */
    @PostMapping("/active")
    public Map<String, Object> setActive(@RequestParam String pluginId,
                                         @RequestParam boolean value) {
        states.setActive(pluginId, value);
        return states.describe(pluginId);
    }

    /**
     * 重载：换掉插件 jar 之后，不需要重启应用就能让新版本生效
     * （SBP 会重建插件的类加载器与 Spring 子容器，随后补跑该插件的新迁移）。
     *
     * <p>放在这里而不是直接开放 SBP 自带的 {@code /sbp/reload/*}：管理面只留一个入口，
     * 于是"停用的插件端点被拦下"这条闸门规则对重载同样成立（{@code reload} 已进白名单）。
     * 官方的 {@code /sbp/**} 仍然可用，供运维/脚本使用，见 application.yml 的注释。
     */
    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestParam String pluginId) {
        registry.get(pluginId);
        states.reload(pluginId);
        return states.describe(pluginId);
    }

    @GetMapping("/get-health")
    public PluginHealth health(@RequestParam String pluginId) {
        registry.get(pluginId);
        return states.healthOf(pluginId);
    }
}
