package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record BatchCardUpdateRequest(List<CardUpdate> cards) {
    public BatchCardUpdateRequest {
        cards = cards == null ? List.of() : cards;
    }
}
