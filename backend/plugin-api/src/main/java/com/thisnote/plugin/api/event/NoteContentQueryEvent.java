package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;
import com.thisnote.plugin.api.notes.dto.NoteContentDTO;

import java.util.concurrent.CompletableFuture;

/**
 * 按 noteId 查询笔记与卡片正文，由 notes 侧监听填充。
 */
public record NoteContentQueryEvent(String noteId,
                                    CompletableFuture<NoteContentDTO> result)
        implements PluginQuery<NoteContentDTO> {

    public NoteContentQueryEvent {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
    }
}
