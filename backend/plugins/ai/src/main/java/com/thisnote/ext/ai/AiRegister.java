package com.thisnote.ext.ai;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class AiRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("AI 配置见插件管理页");
    }
}
