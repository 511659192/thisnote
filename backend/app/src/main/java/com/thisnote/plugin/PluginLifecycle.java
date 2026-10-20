package com.thisnote.plugin;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件启动生命周期：<b>发现 → 激活</b>。
 *
 * <p>为什么要有这个类：这几件事以前是散着的 —— 发现藏在 {@code PluginRegistry} 的构造器里，
 * 激活是「读的时候顺便算」，次序确实存在，但只存在于"类被谁先
 * 实例化"这种隐式知识里：看一眼启动日志说不清走到哪一步。
 *
 * <p>现在两个阶段在一处、按固定顺序执行，每个阶段都留下日志，并把结果记进一份可读的启动报告
 * （{@link #report()}），测试可以直接断言它。
 *
 * <p>时机用 {@link SmartInitializingSingleton}：所有单例都装配完之后、Web 容器开始接流量之前 ——
 * 这样第一个请求不会撞上还没就绪的插件。
 */
@Component
@Slf4j
public class PluginLifecycle implements SmartInitializingSingleton {

    /**
     * 阶段名，按执行顺序（日志与测试共用这一份，避免两处各写一遍）。
     */
    public static final List<String> PHASES = List.of("发现", "激活");

    private final PluginRegistry registry;
    private final PluginStateService states;

    /**
     * 最近一次启动的报告；启动完成后只读。
     */
    private volatile Map<String, Object> report = Map.of("phases", List.of(), "plugins", List.of());

    public PluginLifecycle(PluginRegistry registry, PluginStateService states) {
        this.registry = registry;
        this.states = states;
    }

    @Override
    public void afterSingletonsInstantiated() {
        run();
    }

    /**
     * 按固定顺序跑完两个阶段，返回启动报告（重复调用是幂等的：迁移本身按版本记账）。
     */
    public synchronized Map<String, Object> run() {
        List<PluginWrapper> plugins = registry.all();

        // 1) 发现
        for (PluginWrapper wrapper : plugins) {
            var d = wrapper.getDescriptor();
            log.info("[插件启动 1/2 发现] {} undeactivatable={} v{}",
                    d.getPluginId(), registry.undeactivatable(d.getPluginId()),
                    d.getVersion());
        }

        // 2) 激活：汇总当前启停状态
        List<Map<String, Object>> described = plugins.stream()
                .map(wrapper -> {
                    String id = wrapper.getDescriptor().getPluginId();
                    var dependencies = wrapper.getDescriptor().getDependencies();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", id);
                    row.put("undeactivatable", registry.undeactivatable(id));
                    row.put("version", wrapper.getDescriptor().getVersion());
                    row.put("dependencies", dependencies == null ? List.of() : dependencies.stream()
                            .map(dependency -> dependency.getPluginId())
                            .toList());
                    row.put("active", states.isActive(id));
                    return row;
                })
                .toList();
        long activeCount = described.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("active")))
                .count();
        log.info("[插件启动 2/2 激活] 启用 {}/{}（停用的插件其端点会被闸门拦下）", activeCount, plugins.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phases", PHASES);
        result.put("plugins", described);
        report = result;
        return result;
    }

    /**
     * 最近一次启动的报告：阶段顺序与每个插件的身份/启停状态。
     */
    public Map<String, Object> report() {
        return report;
    }
}
