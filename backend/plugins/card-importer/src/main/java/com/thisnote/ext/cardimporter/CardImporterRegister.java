package com.thisnote.ext.cardimporter;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class CardImporterRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("定时导入任务已注册");
    }
}
