package com.thisnote.plugin;

import com.thisnote.plugin.api.meta.PluginMetadata;
import com.thisnote.plugin.api.meta.PluginMetadataReader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 插件注册表：**PF4J 是唯一来源**。
 *
 * <p>它按 id 交出 PF4J 的 {@link PluginWrapper}；管理面静态信息来自插件 jar 内的
 * {@code META-INF/thisnote/plugin.json}，因此插件停用后也能稳定读取显示名、说明和核心保护标记。
 *
 * <p>惰性查询而非构造时快照：SBP 是在应用启动过程中装载插件的（此刻注册表可能还没有插件），
 * 而且运行期还能停用/重载。
 */
@Component
public class PluginRegistry {

    private final ObjectProvider<PluginManager> pluginManager;

    public PluginRegistry(ObjectProvider<PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
    }

    // ------------------------------------------------------------------ PF4J 直通

    /**
     * 当前装载的插件，按 id 索引；每次调用都实时查询。
     */
    private Map<String, PluginWrapper> loaded() {
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return new LinkedHashMap<>();
        }
        return manager.getPlugins().stream().collect(Collectors.toMap(
                wrapper -> wrapper.getDescriptor().getPluginId(),
                wrapper -> wrapper,
                (first, second) -> first,
                LinkedHashMap::new));
    }

    /**
     * 全部插件（PF4J 的 wrapper）；管理面与启动报告从这里取。
     */
    public List<PluginWrapper> all() {
        return List.copyOf(loaded().values());
    }

    /**
     * PF4J 原生描述符（身份/版本/说明/provider）。
     */
    public PluginDescriptor descriptor(String pluginId) {
        return get(pluginId).getDescriptor();
    }

    /**
     * @throws IllegalArgumentException when no plugin carries that id
     */
    public PluginWrapper get(String pluginId) {
        PluginWrapper wrapper = loaded().get(pluginId);
        if (wrapper == null) {
            throw new IllegalArgumentException("未知插件：" + pluginId);
        }
        return wrapper;
    }

    public boolean isRegistered(String pluginId) {
        return loaded().containsKey(pluginId);
    }

    /**
     * 诊断用：已装载插件的 id（顺序稳定）。
     */
    public List<String> ids() {
        return new ArrayList<>(loaded().keySet());
    }

    private PluginMetadata metadata(String pluginId) {
        return PluginMetadataReader.read(get(pluginId));
    }

    /**
     * 管理页显示名。
     */
    public String title(String pluginId) {
        return metadata(pluginId).title();
    }

    /**
     * 一句话说明。
     */
    public String description(String pluginId) {
        return metadata(pluginId).description();
    }

    /**
     * 是否不可停用/不可在线重载（承载主体业务领域的插件）。
     */
    public boolean undeactivatable(String pluginId) {
        return metadata(pluginId).undeactivatable();
    }

    public List<String> dependencies(String pluginId) {
        var dependencies = get(pluginId).getDescriptor().getDependencies();
        return dependencies == null ? List.of() : dependencies.stream()
                .map(dependency -> dependency.getPluginId())
                .toList();
    }

}
