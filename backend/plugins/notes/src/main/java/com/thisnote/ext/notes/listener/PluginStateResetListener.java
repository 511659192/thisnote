package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.NotesPlugin;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            noteService.resetIndexes();
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", NotesPlugin.PLUGIN_ID, e);
        }
    }
}
