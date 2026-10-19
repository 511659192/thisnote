package com.thisnote.ext.graphify;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class GraphifyRegister implements PluginRegister {

    @Override
    public String description() {
        return "使用 Graphify 将笔记、卡片和图片整理为可查询的知识图谱，并以社区提供只读自动标签";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("索引状态与 Graphify 配置见插件页面");
    }

    @Override
    public String title() {
        return "Graphify 知识图谱";
    }
}
