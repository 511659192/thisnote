package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;

import java.util.List;

public record NoteDto(NoteMeta noteMeta, List<Card> cards) {
}
