package com.thisnote.ext.statereset;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class StateResetRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("等待手动触发");
    }
}
