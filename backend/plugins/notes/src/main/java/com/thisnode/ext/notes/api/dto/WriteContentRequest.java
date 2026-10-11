package com.thisnode.ext.notes.api.dto;

public record WriteContentRequest(String contentMd, String baseContentMd) {
    public WriteContentRequest {
        contentMd = contentMd == null ? "" : contentMd;
    }

    public WriteContentRequest() {
        this(null, null);
    }
}
