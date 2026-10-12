package com.thisnode.ext.notes.api.dto;

public record WriteCardContentRequest(String noteId,
                                      String cardId,
                                      String contentMd,
                                      String baseContentMd) {
    public WriteCardContentRequest {
        noteId = noteId == null ? "" : noteId.trim();
        cardId = cardId == null ? "" : cardId.trim();
        contentMd = contentMd == null ? "" : contentMd;
    }
}
