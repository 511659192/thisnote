package com.thisnote.ext.notes.service.listener;

import com.thisnote.ext.notes.NotesPlugin;
import com.thisnote.ext.notes.service.NoteService;
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

    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        try {
            noteService.resetIndexes();
            if (reset.expects(NotesPlugin.PLUGIN_ID)) {
                reset.complete(NotesPlugin.PLUGIN_ID,
                        PluginResetResult.reset(NotesPlugin.PLUGIN_ID, "笔记/卡片/附件索引已重置（文件保留）"));
            }
        } catch (RuntimeException e) {
            if (reset.expects(NotesPlugin.PLUGIN_ID)) {
                reset.complete(NotesPlugin.PLUGIN_ID, PluginResetResult.failed(NotesPlugin.PLUGIN_ID, e));
            }
            log.warn("插件 {} 状态重置失败", NotesPlugin.PLUGIN_ID, e);
        }
    }
}
