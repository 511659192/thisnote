package com.thisnote.ext.tags;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class TagsRegister implements PluginRegister {

    @Override
    public String description() {
        return "为笔记维护独立标签，提供标签页与列表筛选；关闭插件即整体下线，数据保留";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("标签保存在本插件的独立文件中");
    }

    @Override
    public String title() {
        return "标签";
    }
}
