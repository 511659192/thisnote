package com.thisnote.ext.statereset.listener;

import com.thisnote.ext.statereset.StateResetPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        if (reset.expects(StateResetPlugin.PLUGIN_ID)) {
            reset.complete(StateResetPlugin.PLUGIN_ID,
                    PluginResetResult.skipped(StateResetPlugin.PLUGIN_ID, "state-reset 无自有状态"));
        }
    }
}
