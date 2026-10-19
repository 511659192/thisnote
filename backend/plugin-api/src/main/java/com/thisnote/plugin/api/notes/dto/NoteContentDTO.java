package com.thisnote.plugin.api.notes.dto;

import java.util.List;

public record NoteContentDTO(NoteDTO note,
                             List<CardDTO> cards) {

    public NoteContentDTO {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
