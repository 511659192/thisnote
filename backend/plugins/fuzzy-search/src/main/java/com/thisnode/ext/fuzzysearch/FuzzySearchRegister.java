package com.thisnode.ext.fuzzysearch;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + 可选的状态回调。
 *
 * <p>只需 {@code @Extension}：PF4J 的注解处理器把它写进 {@code META-INF/extensions.idx}，
 * SBP 的扩展工厂反射实例化（需要无参构造）。**要注入插件自己的服务时才加** {@code @Component}
 * （那时 SBP 改为从插件容器取 bean）—— 需要注入的插件都这么做。
 */
@Extension
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
