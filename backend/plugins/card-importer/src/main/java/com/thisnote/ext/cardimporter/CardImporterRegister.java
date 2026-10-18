package com.thisnote.ext.cardimporter;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class CardImporterRegister implements PluginRegister {

    @Override
    public String description() {
        return "每 10 秒扫描 notes/cards 中最近有改动且尚未关联到笔记的 Markdown 卡片并导入";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("定时导入任务已注册");
    }

    @Override
    public String title() {
        return "卡片导入";
    }
}
