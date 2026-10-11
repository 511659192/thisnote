package com.thisnode.ext.notes.api.dto;

/** Note-list query parameters, normalized at the API boundary. */
public record NoteQuery(String notebookId, String type, String text, int limit, int offset) {

    public NoteQuery {
        limit = Math.max(1, Math.min(limit <= 0 ? 500 : limit, 2000));
        offset = Math.max(0, offset);
    }

    public static NoteQuery defaults() {
        return new NoteQuery(null, null, null, 500, 0);
    }
}
