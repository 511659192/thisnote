package com.thisnote.ext.attachmentimporter.listener;

import com.thisnote.ext.attachmentimporter.AttachmentImporterPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        if (reset.expects(AttachmentImporterPlugin.PLUGIN_ID)) {
            reset.complete(AttachmentImporterPlugin.PLUGIN_ID,
                    PluginResetResult.skipped(AttachmentImporterPlugin.PLUGIN_ID, "attachment-importer 无自有状态"));
        }
    }
}
