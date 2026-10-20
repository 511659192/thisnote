package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;

import java.util.concurrent.CompletableFuture;

/**
 * 按 noteId 查询笔记与卡片正文，由 notes 侧监听填充。
 */
public record NoteContentFutureEvent(String noteId,
                                     CompletableFuture<NoteCardDTO> result)
        implements PluginFutureEvent<NoteCardDTO> {

    public NoteContentFutureEvent {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
    }
}
