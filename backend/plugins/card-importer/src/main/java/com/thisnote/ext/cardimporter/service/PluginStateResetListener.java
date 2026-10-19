package com.thisnote.ext.cardimporter.service;

import com.thisnote.ext.cardimporter.CardImporterPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        if (reset.expects(CardImporterPlugin.PLUGIN_ID)) {
            reset.complete(CardImporterPlugin.PLUGIN_ID,
                    PluginResetResult.skipped(CardImporterPlugin.PLUGIN_ID, "card-importer 无自有状态"));
        }
    }
}
