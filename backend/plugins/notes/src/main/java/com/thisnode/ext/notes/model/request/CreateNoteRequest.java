package com.thisnode.ext.notes.model.request;

import com.thisnode.ext.notes.model.NotebookPO;

public record CreateNoteRequest(String notebookId) {
    public CreateNoteRequest {
        notebookId = notebookId == null || notebookId.isBlank() ? NotebookPO.DEFAULT_GROUP_ID : notebookId;
    }
}
