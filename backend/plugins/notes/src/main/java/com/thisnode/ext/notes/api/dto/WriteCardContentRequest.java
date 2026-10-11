package com.thisnode.ext.notes.api.dto;

public record WriteCardContentRequest(String contentMd, String baseContentMd) {
    public WriteCardContentRequest {
        contentMd = contentMd == null ? "" : contentMd;
    }

    public WriteCardContentRequest() {
        this(null, null);
    }
}
