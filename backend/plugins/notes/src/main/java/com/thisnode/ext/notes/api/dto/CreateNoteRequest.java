package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Notebook;
import com.thisnode.ext.notes.util.NoteTitleDefaults;

public record CreateNoteRequest(String title, String content, String type, String notebookId) {
    public CreateNoteRequest {
        title = title == null ? "" : title.trim();
        title = title.isBlank() ? NoteTitleDefaults.currentTimeTitle() : title;
        content = content == null ? "" : content;
        type = type == null || type.isBlank() ? "note" : type;
        notebookId = notebookId == null || notebookId.isBlank() ? Notebook.DEFAULT_GROUP_ID : notebookId;
    }
}
