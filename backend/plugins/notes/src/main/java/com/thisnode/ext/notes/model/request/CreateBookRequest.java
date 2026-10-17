package com.thisnode.ext.notes.model.request;

public record CreateBookRequest(String title, String icon) {
    public CreateBookRequest {
        title = title == null || title.isBlank() ? "新建笔记本" : title.trim();
    }
}
