package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;
import com.thisnote.plugin.api.notes.dto.NoteDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 查询全部笔记元数据，由 notes 侧监听填充。
 */
public record NoteListQueryEvent(CompletableFuture<List<NoteDTO>> result)
        implements PluginQuery<List<NoteDTO>> {

    public NoteListQueryEvent {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
    }
}
