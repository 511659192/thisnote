package com.thisnote.ext.attachmentimporter;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class AttachmentImporterRegister implements PluginRegister {

    @Override
    public String description() {
        return "每 10 秒扫描附件目录，把未被卡片引用的图片自动生成笔记与卡片";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("每 10 秒扫描 notes/attachments");
    }

    @Override
    public String title() {
        return "附件导入";
    }
}
