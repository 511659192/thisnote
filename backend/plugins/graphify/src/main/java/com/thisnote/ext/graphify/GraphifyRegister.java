package com.thisnote.ext.graphify;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class GraphifyRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("索引状态与 Graphify 配置见插件页面");
    }
}
