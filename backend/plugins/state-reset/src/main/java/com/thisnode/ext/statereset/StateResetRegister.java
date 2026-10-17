package com.thisnode.ext.statereset;

import com.thisnode.plugin.api.lifecycle.PluginHealth;
import com.thisnode.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class StateResetRegister implements PluginRegister {

    @Override
    public String description() {
        return "手动触发一次初始化事件，各插件把状态文件重置为初始内容";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("等待手动触发");
    }

    @Override
    public String title() {
        return "状态重置";
    }
}
