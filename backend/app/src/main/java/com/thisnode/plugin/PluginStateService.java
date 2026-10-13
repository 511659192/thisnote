package com.thisnode.plugin;

import com.thisnode.plugin.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件全局启停与管理信息的门面。
 *
 * <p><b>全局启停的真相源是 PF4J/SBP</b>，不是数据库：插件 jar 在不在、有没有被管理上停用，
 * 由 PF4J 的插件状态（以及它自己的停用名单 {@code plugins/disabled.txt}）决定。
 * 这里只做转发与通知，不再另存一份标记 —— 两份真相源迟早会打架。
 *
 * <p>插件业务状态由插件自己的 API 与存储管理；平台只负责插件级启停与管理视图。
 */
@Service
public class PluginStateService {

    private static final Logger log = LoggerFactory.getLogger(PluginStateService.class);

    private final PluginRegistry registry;
    private final org.springframework.beans.factory.ObjectProvider<org.pf4j.PluginManager> pluginManager;

    public PluginStateService(PluginRegistry registry,
                              org.springframework.beans.factory.ObjectProvider<org.pf4j.PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
        this.registry = registry;
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
        if (!active && registry.isRegistered(pluginId) && registry.undeactivatable(pluginId)) {
            throw new com.thisnode.plugin.api.ConflictException(
                    "「" + registry.title(pluginId) + "」承载主体业务，不允许停用");
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

    // ------------------------------------------------------------------ 重载

    /**
     * 重载一个插件：SBP/PF4J 会**重建插件的类加载器**并重新启动它的 Spring 子容器，
     * 所以"把磁盘上的 jar 换成新版本"可以不重启应用就生效（这也是 PF4J 的核心能力之一）。
     *
     * <p>配套的一件事：重载后**补一次全局状态通知** —— 新实例没经历过启停回调，不通知就等于不知道
     * 自己在服务。
     */
    public void reload(String pluginId) {
        if (registry.isRegistered(pluginId) && registry.undeactivatable(pluginId)) {
            // 不可停用的插件是全部业务端点的宿主：重载会重建它的 Spring 子容器，期间路由与索引短暂缺失。
            // （早先的理由是"兄弟插件共享它导入的门面"，那层共享已经不存在了。）拒绝并提示重启。
            throw new com.thisnode.plugin.api.ConflictException(
                    "「" + registry.title(pluginId) + "」承载主体业务，不支持在线重载（它承载全部业务端点）；更换 notes 插件后请重启应用生效");
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
    public PluginHealth healthOf(String pluginId) {
        if (isExternal(pluginId) && !isActive(pluginId)) {
            return PluginHealth.unknown("插件已停用，容器未运行");
        }
        try {
            return registry.health(pluginId);
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
            registry.globalStateChanged(pluginId, active);
        } catch (RuntimeException e) {
            log.warn("通知插件 {} 全局状态={} 失败：{}", pluginId, active, e.toString());
        }
    }

    /** 该 id 是不是 PF4J/SBP 装载的外置插件（内置插件没有自己的类加载器）。 */
    private boolean isExternal(String pluginId) {
        return registry.pluginClassLoader(pluginId) != null;
    }

    // ------------------------------------------------------------------ 管理视图

    /**
     * 管理页要的所有东西：描述 + 全局启停 + 健康 + 磁盘版本。
     */
    public Map<String, Object> describe(String pluginId) {
        Map<String, Object> out = new LinkedHashMap<>();
        org.pf4j.PluginDescriptor descriptor = registry.descriptor(pluginId);
        out.put("id", pluginId);
        out.put("title", registry.title(pluginId));
        out.put("description", registry.description(pluginId));
        out.put("undeactivatable", registry.undeactivatable(pluginId));
        out.put("version", descriptor.getVersion());
        out.put("active", isActive(pluginId));
        PluginHealth health = healthOf(pluginId);
        out.put("health", Map.of("status", health.status(), "message", health.message()));
        // 磁盘上那个 jar 的版本：与 version（正在跑的实例）不同就说明"换了 jar，还没重载"。
        String diskVersion = diskVersion(pluginId);
        out.put("diskVersion", diskVersion);
        out.put("reloadAvailable", diskVersion != null && !diskVersion.equals(descriptor.getVersion()));
        return out;
    }
}
