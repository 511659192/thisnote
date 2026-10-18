package com.thisnote.ext.memorycycle;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的管理信息（唯一的 PF4J 扩展点）：显示名、说明与健康状态。
 *
 * <p>本扩展不需要注入插件服务，因此只需 {@code @Extension}。
 */
@Extension
public class MemoryCycleRegister implements PluginRegister {

    @Override
    public String description() {
        return "参考 Anki 的 SM-2 间隔重复：按卡片安排复习，遗忘即回退，间隔随掌握程度增长";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("开启记忆周期的卡片会按间隔出现在复习页");
    }

    @Override
    public String title() {
        return "记忆周期";
    }
}
