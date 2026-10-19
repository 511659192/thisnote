package com.thisnote.ext.baidusync;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class BaiduSyncRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("同步状态由插件设置页管理");
    }
}
