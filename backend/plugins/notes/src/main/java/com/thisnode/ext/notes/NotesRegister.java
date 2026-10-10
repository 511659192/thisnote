package com.thisnode.ext.notes;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + 可选的停用约束与状态回调。
 *
 * <p>写成插件容器里的 bean（@Component）—— SBP 的扩展工厂优先从容器取 bean，
 * 于是扩展类也能注入插件自己的服务。
 */
@Extension
@Component
public class NotesRegister implements PluginRegister {

    @Override
    public String description() {
        return "笔记 / 卡片 / 搜索 / 标签 / 附件 / 快照 / 导入导出 —— 主体原有的业务领域，以插件形态交付";
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
