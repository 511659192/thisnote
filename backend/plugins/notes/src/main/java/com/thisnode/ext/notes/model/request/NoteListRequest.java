package com.thisnode.ext.notes.model.request;

/** Note-list query parameters, normalized at the API boundary. */
public record NoteListRequest(String bookId, String text, int limit, int offset) {

    public NoteListRequest {
        limit = Math.max(1, Math.min(limit <= 0 ? 500 : limit, 2000));
        offset = Math.max(0, offset);
    }

    public static NoteListRequest defaults() {
        return new NoteListRequest(null, null, 500, 0);
    }
}
