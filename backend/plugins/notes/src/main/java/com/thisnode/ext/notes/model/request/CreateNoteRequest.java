package com.thisnode.ext.notes.model.request;

import com.thisnode.ext.notes.model.Notebook;

public record CreateNoteRequest(String type, String notebookId) {
    public CreateNoteRequest {
        type = type == null || type.isBlank() ? "note" : type;
        notebookId = notebookId == null || notebookId.isBlank() ? Notebook.DEFAULT_GROUP_ID : notebookId;
    }
}
