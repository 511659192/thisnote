package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.NotesPlugin;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final NoteRepository noteRepository;
    private final CardRepository cardRepository;
    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            noteRepository.reset();
            cardRepository.reset();
            attachmentService.reset();
            log.info("笔记索引、卡片索引与附件索引已重置");
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", NotesPlugin.PLUGIN_ID, e);
        }
    }
}
