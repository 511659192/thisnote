package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record BatchUpdateCardsRequest(String noteId, List<UpdateCardRequest> cards) {
    public BatchUpdateCardsRequest {
        noteId = noteId == null ? "" : noteId.trim();
        cards = cards == null ? List.of() : cards;
    }
}
