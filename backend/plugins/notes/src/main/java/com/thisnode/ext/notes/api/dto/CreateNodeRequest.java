package com.thisnode.ext.notes.api.dto;

public record CreateNodeRequest(String title, String contentMd, String type, String notebookId) {
    public CreateNodeRequest {
        title = title == null ? "" : title.trim();
        title = title.isBlank() ? RequestDefaults.currentTimeTitle() : title;
        contentMd = contentMd == null ? "" : contentMd;
        type = type == null || type.isBlank() ? "note" : type;
        notebookId = notebookId == null || notebookId.isBlank() ? null : notebookId;
    }
}
