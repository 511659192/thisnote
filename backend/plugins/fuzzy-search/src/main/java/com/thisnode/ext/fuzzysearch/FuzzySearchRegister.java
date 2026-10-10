package com.thisnode.ext.fuzzysearch;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + 可选的状态回调。
 *
 * <p>写成插件容器里的 bean（@Component）—— SBP 的扩展工厂优先从容器取 bean，
 * 于是扩展类也能注入插件自己的服务。
 */
@Extension
@Component
public class FuzzySearchRegister implements PluginRegister {

    @Override
    public String description() {
        return "顶栏跨标题/摘要的模糊查询，下拉结果直达笔记；纯界面侧实现，关闭即入口消失，无任何数据";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("纯界面侧能力，无后端状态");
    }

    @Override
    public String title() {
        return "模糊搜索";
    }
}
