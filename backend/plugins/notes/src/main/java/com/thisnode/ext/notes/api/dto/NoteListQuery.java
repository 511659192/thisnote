package com.thisnode.ext.notes.api.dto;

/** Note-list query parameters, normalized at the API boundary. */
public record NoteListQuery(String notebookId, String type, String text, int limit, int offset) {

    public NoteListQuery {
        limit = Math.max(1, Math.min(limit <= 0 ? 500 : limit, 2000));
        offset = Math.max(0, offset);
    }

    public static NoteListQuery defaults() {
        return new NoteListQuery(null, null, null, 500, 0);
    }
}
