package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

import java.util.List;

public record CardsPageDto(String noteId, String noteTitle, List<Card> cards, long updatedAt) {
}
