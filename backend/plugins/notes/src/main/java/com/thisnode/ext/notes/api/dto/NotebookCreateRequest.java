package com.thisnode.ext.notes.api.dto;

public record NotebookCreateRequest(String title, String icon) {
    public NotebookCreateRequest {
        title = title == null || title.isBlank() ? "新建笔记本" : title.trim();
    }
}
