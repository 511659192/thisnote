package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteDTO;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 将一个标题候选应用到指定笔记；更新与持久化由 notes 插件完成。
 */
public record NoteTitleUpdateFutureEvent(String noteId,
                                         String title,
                                         CompletableFuture<NoteDTO> updated)
        implements PluginFutureEvent<NoteDTO> {

    public NoteTitleUpdateFutureEvent {
        noteId = Objects.requireNonNull(noteId, "noteId").trim();
        title = Objects.requireNonNull(title, "title").trim();
        if (noteId.isEmpty()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        updated = Objects.requireNonNull(updated, "updated");
    }

    public static NoteTitleUpdateFutureEvent request(String noteId,
                                                     String title) {
        return new NoteTitleUpdateFutureEvent(noteId, title, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<NoteDTO> result() {
        return updated;
    }
}
