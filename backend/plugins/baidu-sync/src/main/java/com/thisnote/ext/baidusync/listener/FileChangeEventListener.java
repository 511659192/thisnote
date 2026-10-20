package com.thisnote.ext.baidusync.listener;

import com.alibaba.fastjson2.JSON;
import com.thisnote.ext.baidusync.BaiduSyncPlugin;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import com.thisnote.plugin.api.event.FileChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class FileChangeEventListener
        implements ApplicationListener<PayloadApplicationEvent<FileChangeEvent>> {

    private final BaiduSyncService syncService;

    @Async
    @Override
    public void onApplicationEvent(PayloadApplicationEvent<FileChangeEvent> event) {
        FileChangeEvent change = event.getPayload();
        try {
            // 忽略本插件触发的变更，避免同步写入反复唤起自身。
            if (!BaiduSyncPlugin.PLUGIN_ID.equals(change.sourcePluginId())) {
                log.info("监听到文件变更事件，同步开始：{}", JSON.toJSONString(change));
                syncService.requestSyncAfterChange(change);
            } else {
                log.debug("忽略百度同步自身产生的文件变更：{}", change.url());
            }
        } catch (Exception e) {
            log.error("处理文件变更事件失败：{}", change.url(), e);
        }
    }
}
