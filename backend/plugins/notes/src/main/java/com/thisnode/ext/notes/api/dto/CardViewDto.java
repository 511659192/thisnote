package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

public record CardViewDto(String fileName, Card.CardMeta card, String contentMd, String noteTitle) {
}
