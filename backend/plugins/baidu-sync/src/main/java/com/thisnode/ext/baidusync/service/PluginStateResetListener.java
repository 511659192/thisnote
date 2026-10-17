package com.thisnode.ext.baidusync.service;

import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 状态重置：删除同步基线（保留授权凭据），注册在主容器上。
 */
@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final BaiduSyncService syncService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        syncService.clearSyncState();
        log.info("收到状态重置事件（来自 {}）：同步基线已清理", event.getPayload().sourcePluginId());
    }
}
