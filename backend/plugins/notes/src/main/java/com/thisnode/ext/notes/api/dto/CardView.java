package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

public record CardView(Card.CardMeta card, String contentMd, String noteTitle) {
}
