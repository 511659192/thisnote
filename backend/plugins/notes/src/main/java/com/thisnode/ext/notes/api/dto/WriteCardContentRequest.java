package com.thisnode.ext.notes.api.dto;

public record WriteCardContentRequest(String noteId,
                                      String cardFileName,
                                      String contentMd,
                                      String baseContentMd) {
    public WriteCardContentRequest {
        noteId = noteId == null ? "" : noteId.trim();
        cardFileName = cardFileName == null ? "" : cardFileName.trim();
        contentMd = contentMd == null ? "" : contentMd;
    }

    public WriteCardContentRequest() {
        this(null, null, null, null);
    }
}
