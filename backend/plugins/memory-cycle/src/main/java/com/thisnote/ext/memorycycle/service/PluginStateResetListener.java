package com.thisnote.ext.memorycycle.service;

import com.thisnote.ext.memorycycle.MemoryCyclePlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final MemoryCycleService memoryCycleService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        try {
            memoryCycleService.clearAll();
            if (reset.expects(MemoryCyclePlugin.PLUGIN_ID)) {
                reset.complete(MemoryCyclePlugin.PLUGIN_ID,
                        PluginResetResult.reset(MemoryCyclePlugin.PLUGIN_ID, "记忆周期排期与复习历史已清空"));
            }
        } catch (RuntimeException e) {
            if (reset.expects(MemoryCyclePlugin.PLUGIN_ID)) {
                reset.complete(MemoryCyclePlugin.PLUGIN_ID,
                        PluginResetResult.failed(MemoryCyclePlugin.PLUGIN_ID, e));
            }
            log.warn("插件 {} 状态重置失败", MemoryCyclePlugin.PLUGIN_ID, e);
        }
    }
}
