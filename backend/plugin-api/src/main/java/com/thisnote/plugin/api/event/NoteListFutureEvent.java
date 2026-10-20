package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 查询全部笔记元数据，由 notes 侧监听填充。
 */
public record NoteListFutureEvent(CompletableFuture<List<NoteDTO>> result)
        implements PluginFutureEvent<List<NoteDTO>> {

    public NoteListFutureEvent {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
    }
}
