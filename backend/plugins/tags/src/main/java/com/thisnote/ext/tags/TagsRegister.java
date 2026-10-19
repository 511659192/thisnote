package com.thisnote.ext.tags;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class TagsRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("标签保存在本插件的独立文件中");
    }
}
