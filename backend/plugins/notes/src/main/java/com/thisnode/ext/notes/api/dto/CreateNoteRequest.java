package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.util.NoteTitleDefaults;

public record CreateNoteRequest(String title, String contentMd, String type, String notebookId) {
    public CreateNoteRequest {
        title = title == null ? "" : title.trim();
        title = title.isBlank() ? NoteTitleDefaults.currentTimeTitle() : title;
        contentMd = contentMd == null ? "" : contentMd;
        type = type == null || type.isBlank() ? "note" : type;
        notebookId = notebookId == null || notebookId.isBlank() ? null : notebookId;
    }
}
