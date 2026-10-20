package com.thisnote.ext.baidusync.listener;

import com.thisnote.ext.baidusync.BaiduSyncPlugin;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final BaiduSyncService syncService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            syncService.clearSyncState();
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", BaiduSyncPlugin.PLUGIN_ID, e);
        }
    }
}
