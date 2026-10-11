package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record CardsPageDto(String noteId, String noteTitle, List<CardViewDto> cards, int maxCardsPerNote, long updatedAt) {
}
