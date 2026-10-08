package com.thisnode.plugin;

import com.thisnode.plugin.api.Plugin;
import com.thisnode.plugin.api.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件注册表：把 PF4J/SBP 装载的插件暴露成契约里的 {@link Plugin}。
 *
 * <p><b>唯一来源是 PF4J</b>：插件是 {@code plugins/} 目录里的 jar，由 PF4J 用它自己的
 * ClassLoader 装载。这里只做一层适配——把 PF4J 的插件实例按 id 交出来，并按契约版本过滤。
 *
 * <p>为什么是<b>惰性查询</b>而不是构造时快照：SBP 是在应用启动过程中装载插件的，注册表构造时
 * 插件可能还没起来；而且插件可以运行期装卸（管理页停用/启用、换 jar 重载），快照会立刻过期。
 *
 * <p>契约版本不匹配的插件**跳过并告警**：它是运行期装进来的，不该因为一个插件把进程搞崩，
 * 也不该让它的端点悄悄以错误的接口对外服务。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final ObjectProvider<PluginManager> pluginManager;

    public PluginRegistry(ObjectProvider<PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 当前装载的全部插件，按 id 索引；每次调用都实时查询。 */
    private Map<String, Plugin> loaded() {
        Map<String, Plugin> out = new LinkedHashMap<>();
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return out;
        }
        for (PluginWrapper wrapper : manager.getPlugins()) {
            if (!(wrapper.getPlugin() instanceof Plugin plugin)) {
                continue;
            }
            PluginDescriptor descriptor = plugin.descriptor();
            if (descriptor.apiVersion() != Plugin.API_VERSION) {
                log.warn("插件 {} 的 apiVersion={} 与本核心 {} 不匹配，已跳过",
                        descriptor.id(), descriptor.apiVersion(), Plugin.API_VERSION);
                continue;
            }
            out.putIfAbsent(descriptor.id(), plugin);
        }
        return out;
    }

    /**
     * 外置插件的类加载器；未找到该 id 返回 null。
     *
     * <p>插件迁移脚本与 mapper XML 都要按这个加载器去解析：外置插件的资源在它的 jar 里，
     * 默认解析器（应用类加载器）看不到。
     */
    public ClassLoader pluginClassLoader(String pluginId) {
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return null;
        }
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        return wrapper == null ? null : wrapper.getPluginClassLoader();
    }

    public List<PluginDescriptor> descriptors() {
        return loaded().values().stream().map(Plugin::descriptor).toList();
    }

    /** 全部插件实例（管理面要读它们的健康与配置 schema）。 */
    public List<Plugin> all() {
        return List.copyOf(loaded().values());
    }

    public boolean isRegistered(String pluginId) {
        return loaded().containsKey(pluginId);
    }

    /**
     * @throws IllegalArgumentException when no plugin carries that id
     */
    public Plugin get(String pluginId) {
        Plugin plugin = loaded().get(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("未知插件：" + pluginId);
        }
        return plugin;
    }

    /** 诊断用：已装载插件的 id（顺序稳定）。 */
    public List<String> ids() {
        return new ArrayList<>(loaded().keySet());
    }
}
