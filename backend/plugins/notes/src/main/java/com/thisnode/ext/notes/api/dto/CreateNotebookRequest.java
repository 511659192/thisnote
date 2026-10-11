package com.thisnode.ext.notes.api.dto;

public record CreateNotebookRequest(String title, String icon) {
    public CreateNotebookRequest {
        title = title == null || title.isBlank() ? "新建笔记本" : title.trim();
    }
}
