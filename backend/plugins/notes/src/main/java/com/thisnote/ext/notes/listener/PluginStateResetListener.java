package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.NotesPlugin;
import com.thisnote.ext.notes.repository.BookRepository;
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
    private final BookRepository bookRepository;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            cardRepository.reset();
            attachmentService.reset();
            noteRepository.reset();
            bookRepository.reset();
            log.info("笔记、卡片、附件与分组数据已清空，默认分组已重建");
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", NotesPlugin.PLUGIN_ID, e);
        }
    }
}
