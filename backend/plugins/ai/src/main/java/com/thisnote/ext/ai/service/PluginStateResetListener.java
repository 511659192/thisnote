package com.thisnote.ext.ai.service;

import com.thisnote.ext.ai.AiPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        if (reset.expects(AiPlugin.PLUGIN_ID)) {
            reset.complete(AiPlugin.PLUGIN_ID,
                    PluginResetResult.skipped(AiPlugin.PLUGIN_ID, "AI 配置与凭据保留"));
        }
    }
}
