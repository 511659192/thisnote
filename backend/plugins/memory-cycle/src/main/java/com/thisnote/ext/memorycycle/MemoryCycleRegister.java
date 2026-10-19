package com.thisnote.ext.memorycycle;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的运行时健康状态。
 *
 * <p>本扩展不需要注入插件服务，因此只需 {@code @Extension}。
 */
@Extension
public class MemoryCycleRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("开启记忆周期的卡片会按间隔出现在复习页");
    }
}
