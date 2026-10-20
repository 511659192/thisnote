package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.GraphifyPlugin;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            indexService.resetState().whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Throwable error) {
        log.error("插件 {} 状态重置失败", GraphifyPlugin.PLUGIN_ID, error);
    }
}
