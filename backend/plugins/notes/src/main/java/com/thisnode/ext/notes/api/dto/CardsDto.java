package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record CardsDto(String noteId, String noteTitle, List<CardView> cards, int maxCardsPerNote, long updatedAt) {
}
