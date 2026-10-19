package com.thisnote.ext.attachmentimporter;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

@Extension
public class AttachmentImporterRegister implements PluginRegister {

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("每 10 秒扫描 notes/attachments");
    }
}
