package com.thisnote.plugin.api.notes.dto;

import java.util.List;

public record NoteCardDTO(NoteDTO note,
                          List<CardDTO> cards) {

    public NoteCardDTO {
        cards = cards == null ? List.of() : cards;
    }
}
