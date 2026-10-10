package com.thisnode.plugin;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.PluginDescriptor;
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
 * 插件注册表：**PF4J 是唯一来源**。
 *
 * <p>它只做两件事：按 id 交出 PF4J 的 {@link PluginWrapper}；按类型取出插件贡献的 PF4J
 * 扩展（{@code @Extension} 类 —— SBP 会用插件自己的 Spring 容器实例化它们，所以扩展类可以
 * 注入插件容器里的服务）。
 *
 * <p><b>身份/版本/说明一律读 PF4J 描述符</b>（{@code plugin.properties} 的 plugin.id /
 * plugin.version / plugin.provider / plugin.description）。PF4J 没有的两项 —— 管理页显示名、
 * 「不可停用」—— 来自 {@link PluginRegister} 扩展；缺失时回落为「显示名 = id、可停用」。
 *
 * <p><b>契约版本也走框架</b>：插件在 {@code plugin.requires} 里声明（支持 semver 范围，
 * 例如 {@code >=3.0.0}），这里用 PF4J 的 {@code VersionManager} 校验。不匹配的插件**跳过并告警**
 * —— 它是运行期装进来的，不该把进程搞崩，也不该以错误的接口对外服务。
 *
 * <p>惰性查询而非构造时快照：SBP 是在应用启动过程中装载插件的（此刻注册表可能还没有插件），
 * 而且运行期还能停用/重载。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final ObjectProvider<PluginManager> pluginManager;

    public PluginRegistry(ObjectProvider<PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
    }

    // ------------------------------------------------------------------ PF4J 直通

    /** 当前装载且满足契约版本的插件，按 id 索引；每次调用都实时查询。 */
    private Map<String, PluginWrapper> loaded() {
        Map<String, PluginWrapper> out = new LinkedHashMap<>();
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return out;
        }
        for (PluginWrapper wrapper : manager.getPlugins()) {
            String id = wrapper.getDescriptor().getPluginId();
            if (!contractSatisfied(wrapper)) {
                continue;
            }
            out.putIfAbsent(id, wrapper);
        }
        return out;
    }

    /**
     * 插件的 {@code plugin.requires} 是否被本核心的契约版本满足。
     *
     * <p>没声明 requires 的插件不校验（等于"不挑版本"）—— 校验机制用的是 PF4J 自己的
     * {@code VersionManager}，所以范围表达式（{@code >=3.0.0}、{@code [3.0,4.0)}）都由框架解析。
     */
    private boolean contractSatisfied(PluginWrapper wrapper) {
        String requires = wrapper.getDescriptor().getRequires();
        if (requires == null || requires.isBlank()) {
            return true;
        }
        PluginManager manager = pluginManager.getIfAvailable();
        boolean ok = manager != null
                && manager.getVersionManager().checkVersionConstraint(PluginRegister.CONTRACT_VERSION, requires);
        if (!ok) {
            log.warn("插件 {} 要求 plugin.requires={}，本核心契约版本是 {} —— 已跳过",
                    wrapper.getDescriptor().getPluginId(), requires, PluginRegister.CONTRACT_VERSION);
        }
        return ok;
    }

    /** 全部插件（PF4J 的 wrapper）；管理面与启动报告从这里取。 */
    public List<PluginWrapper> all() {
        return List.copyOf(loaded().values());
    }

    /** PF4J 原生描述符（身份/版本/说明/provider/requires）。 */
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

    /** 诊断用：已装载插件的 id（顺序稳定）。 */
    public List<String> ids() {
        return new ArrayList<>(loaded().keySet());
    }

    /**
     * 外置插件的类加载器；未找到该 id 返回 null。
     *
     * <p>插件要按它去解析自己 jar 里的资源（默认解析器是应用类加载器，看不到插件内部）。
     */
    public ClassLoader pluginClassLoader(String pluginId) {
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return null;
        }
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        return wrapper == null ? null : wrapper.getPluginClassLoader();
    }

    // ------------------------------------------------------------------ 扩展点查询

    /**
     * 取插件的 {@link PluginRegister} 扩展（PF4J 原生 {@code getExtensions(type, pluginId)}）。
     *
     * <p>它承载插件的全部能力；插件没提供时返回 {@code null}，各访问器按默认语义回落。
     */
    private PluginRegister register(String pluginId) {
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null || manager.getPlugin(pluginId) == null) {
            return null;
        }
        List<PluginRegister> found = manager.getExtensions(PluginRegister.class, pluginId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 管理页显示名：来自 {@link PluginRegister}；没有该扩展时回落为插件 id。 */
    public String title(String pluginId) {
        PluginRegister register = register(pluginId);
        return register == null ? pluginId : register.title();
    }

    /**
     * 一句话说明：优先取 {@link PluginRegister#description()}（Java 源码 UTF-8，中文可靠）；
     * 没有该扩展时回落到 PF4J 描述符的 {@code plugin.description}。
     */
    public String description(String pluginId) {
        PluginRegister register = register(pluginId);
        if (register != null && register.description() != null && !register.description().isBlank()) {
            return register.description();
        }
        String fromDescriptor = get(pluginId).getDescriptor().getPluginDescription();
        return fromDescriptor == null ? "" : fromDescriptor;
    }

    /** 是否不可停用/不可在线重载（承载主体业务领域的插件）。 */
    public boolean undeactivatable(String pluginId) {
        PluginRegister register = register(pluginId);
        return register != null && register.undeactivatable();
    }

    /** 插件自报健康；没提供扩展即视为「运行正常」。 */
    public PluginHealth health(String pluginId) {
        PluginRegister register = register(pluginId);
        return register == null ? PluginHealth.ok("运行正常") : register.health();
    }

    /** 通知插件的全局启停（尽力而为：没有提供扩展就跳过）。 */
    public void globalStateChanged(String pluginId, boolean enabled) {
        PluginRegister register = register(pluginId);
        if (register != null) {
            register.onGlobalStateChanged(enabled);
        }
    }

    /** 通知插件的 per-entity 状态翻转（没有提供扩展就跳过）。 */
    public void entityStateChanged(String pluginId, String entityId, boolean enabled) {
        PluginRegister register = register(pluginId);
        if (register != null) {
            register.onEntityStateChanged(entityId, enabled);
        }
    }
}
