package com.thisnode.ext.baidusync.service;

import com.thisnode.ext.baidusync.BaiduSyncPlugin;
import com.thisnode.plugin.api.FileChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
public final class BaiduSyncChangeEventListener
        implements ApplicationListener<PayloadApplicationEvent<FileChangeEvent>> {

    private final BaiduSyncService syncService;

    public BaiduSyncChangeEventListener(BaiduSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<FileChangeEvent> event) {
        FileChangeEvent change = event.getPayload();
        try {
            // 忽略本插件触发的变更，避免同步写入反复唤起自身。
            if (!BaiduSyncPlugin.PLUGIN_ID.equals(change.sourcePluginId()) && syncService.isNotesRootFileChange(change)) {
                log.debug("收到文件变更事件：sourcePluginId={}, operation={}, url={}, previousUrl={}",
                          change.sourcePluginId(),
                          change.operation(),
                          change.url(),
                          change.previousUrl());
                syncService.requestSyncAfterChange(change);
            }
        } catch (RuntimeException e) {
            log.warn("处理文件变更事件失败：{}", change.url(), e);
        }
    }
}
