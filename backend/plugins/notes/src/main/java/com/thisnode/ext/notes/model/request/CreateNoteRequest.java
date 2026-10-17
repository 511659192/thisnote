package com.thisnode.ext.notes.model.request;

import com.thisnode.ext.notes.model.BookPO;

public record CreateNoteRequest(String bookId) {
    public CreateNoteRequest {
        bookId = bookId == null || bookId.isBlank() ? BookPO.DEFAULT_GROUP_ID : bookId;
    }
}
