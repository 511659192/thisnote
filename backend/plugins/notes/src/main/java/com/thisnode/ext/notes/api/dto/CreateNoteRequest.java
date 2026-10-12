package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Notebook;

public record CreateNoteRequest(String type, String notebookId) {
    public CreateNoteRequest {
        type = type == null || type.isBlank() ? "note" : type;
        notebookId = notebookId == null || notebookId.isBlank() ? Notebook.DEFAULT_GROUP_ID : notebookId;
    }
}
