package com.thisnote.ext.notes.model;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.BookDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface NotesMapper {

    CardDTO.CardMetaDTO toCardMetaDTO(CardPO.CardMeta meta);

    @Mapping(target = "sourceFileName", expression = "java(card.sourceFileName())")
    CardDTO toCardDTO(CardPO card);

    NoteDTO toNoteDTO(NotePO note);

    BookDTO toBookDTO(BookPO book);
}
