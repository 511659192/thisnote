package com.thisnode.ext.notes.model.request;

import java.util.List;

public record BatchUpdateCardsRequest(String noteId, List<UpdateCardRequest> cards) {
    public BatchUpdateCardsRequest {
        noteId = noteId == null ? "" : noteId.trim();
        cards = cards == null ? List.of() : cards;
    }
}
