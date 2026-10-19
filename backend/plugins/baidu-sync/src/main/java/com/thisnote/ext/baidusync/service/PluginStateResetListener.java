package com.thisnote.ext.baidusync.service;

import com.thisnote.ext.baidusync.BaiduSyncPlugin;
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

    private final BaiduSyncService syncService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        try {
            syncService.clearSyncState();
            if (reset.expects(BaiduSyncPlugin.PLUGIN_ID)) {
                reset.complete(BaiduSyncPlugin.PLUGIN_ID,
                        PluginResetResult.reset(BaiduSyncPlugin.PLUGIN_ID, "同步基线已清理"));
            }
        } catch (RuntimeException e) {
            if (reset.expects(BaiduSyncPlugin.PLUGIN_ID)) {
                reset.complete(BaiduSyncPlugin.PLUGIN_ID,
                        PluginResetResult.failed(BaiduSyncPlugin.PLUGIN_ID, e));
            }
            log.warn("插件 {} 状态重置失败", BaiduSyncPlugin.PLUGIN_ID, e);
        }
    }
}
