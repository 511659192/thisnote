package com.thisnote.ext.memorycycle.listener;

import com.thisnote.ext.memorycycle.MemoryCyclePlugin;
import com.thisnote.ext.memorycycle.service.MemoryCycleService;
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
        try {
            memoryCycleService.clearAll();
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", MemoryCyclePlugin.PLUGIN_ID, e);
        }
    }
}
