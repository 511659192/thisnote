package com.thisnote.ext.ai;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class AiRegister implements PluginRegister {

    @Override
    public String description() {
        return "统一管理模型供应商、API key 与文本/视觉能力；Graphify 与标签插件依赖此核心能力";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("AI 配置见插件管理页");
    }

    @Override
    public String title() {
        return "AI 能力";
    }

    @Override
    public boolean undeactivatable() {
        return true;
    }
}
