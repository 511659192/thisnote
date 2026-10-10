package com.thisnode.plugin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 停用插件的闸门。
 *
 * <p>「停用」如果只是把标志位置成 false、端点照样能调，那它就是个装饰。这个拦截器把
 * {@code /api/plugins/{id}/…} 里<b>插件自有</b>的请求挡在控制器之前；管理面
 * （{@code states} / {@code config} / {@code health} / {@code active}）必须放行，
 * 否则用户停用之后再也点不回来。
 *
 * <p>未注册的插件 id 不拦——那种请求本来就该由控制器给出「未知插件」的答复，
 * 而不是这里冒充一个状态错误。
 */
@Component
public class PluginEndpointGate implements HandlerInterceptor {

    private static final String PREFIX = "/api/plugins/";

    private final PluginRegistry registry;
    private final PluginStateService states;

    public PluginEndpointGate(PluginRegistry registry, PluginStateService states) {
        this.registry = registry;
        this.states = states;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(PREFIX)) {
            return true;
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String pluginId = slash < 0 ? rest : rest.substring(0, slash);
        if (pluginId.isEmpty() || !registry.isRegistered(pluginId)) {
            return true;
        }
        if (isManagementPath(slash < 0 ? "" : rest.substring(slash + 1))) {
            return true;
        }
        if (states.isActive(pluginId)) {
            return true;
        }
        // 不可停用的插件（承载主体业务）永远放行：停用请求在状态服务里已被拒绝（409），
        // 这是双保险 —— 即使 pf4j 层出现意外状态，业务端点也不能被闸门误伤。
        if (registry.undeactivatable(pluginId)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"status":409,"error":"Conflict","message":"插件「%s」已停用，请先在设置 → 插件里启用"}""".formatted(registry.title(pluginId)
                .replace("\"", "'")));
        return false;
    }

    /**
     * 允许的路径是「管理面」：状态、配置、健康、启停本身。
     */
    private boolean isManagementPath(String remainder) {
        if (remainder.isEmpty()) {
            return true;
        }
        int slash = remainder.indexOf('/');
        String segment = slash < 0 ? remainder : remainder.substring(0, slash);
        return PluginController.MANAGEMENT_SEGMENTS.contains(segment);
    }
}
