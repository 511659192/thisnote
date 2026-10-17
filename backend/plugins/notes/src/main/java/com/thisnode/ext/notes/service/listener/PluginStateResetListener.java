package com.thisnode.ext.notes.service.listener;

import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 状态重置：把笔记索引与卡片索引重写为空（卡片 md 文件保留）。
 */
@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        noteService.resetIndexes();
        log.info("收到状态重置事件（来自 {}）：笔记与卡片索引已重置（md 保留）", event.getPayload().sourcePluginId());
    }
}
