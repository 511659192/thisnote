package com.thisnode.ext.notes.model.view;

import com.thisnode.ext.notes.model.Card;
import com.thisnode.ext.notes.model.NoteMeta;

import java.util.List;

public record NoteDto(NoteMeta noteMeta, List<Card> cards) {
}
