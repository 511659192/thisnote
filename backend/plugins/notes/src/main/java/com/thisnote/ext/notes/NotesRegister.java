package com.thisnote.ext.notes;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的管理页与生命周期信息：显示名/说明/健康 + 可选的状态回调。
 *
 * <p>只需 {@code @Extension}：PF4J 的注解处理器把它写进 {@code META-INF/extensions.idx}，
 * SBP 的扩展工厂反射实例化（需要无参构造）。**要注入插件自己的服务时才加** {@code @Component}
 * （那时 SBP 改为从插件容器取 bean）—— 需要注入的插件都这么做。
 */
@Extension
public class NotesRegister implements PluginRegister {

    @Override
    public String description() {
        return "笔记 / 卡片 / 搜索 / 标签 / 快照 / 导入导出 —— 主体原有的业务领域，以插件形态交付";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("核心业务随平台运行");
    }

    @Override
    public String title() {
        return "笔记核心";
    }

    @Override
    public boolean undeactivatable() {
        return true;
    }
}
