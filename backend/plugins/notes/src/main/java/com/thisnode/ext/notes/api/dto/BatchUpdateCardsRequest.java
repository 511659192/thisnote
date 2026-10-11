package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record BatchUpdateCardsRequest(List<UpdateCardRequest> cards) {
    public BatchUpdateCardsRequest {
        cards = cards == null ? List.of() : cards;
    }
}
