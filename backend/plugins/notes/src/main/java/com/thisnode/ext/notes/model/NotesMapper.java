package com.thisnode.ext.notes.model;

import com.thisnode.plugin.api.notes.dto.CardDTO;
import com.thisnode.plugin.api.notes.dto.CardMetaDTO;
import com.thisnode.plugin.api.notes.dto.CardResponseDTO;
import com.thisnode.plugin.api.notes.dto.NoteDTO;
import com.thisnode.plugin.api.notes.dto.NoteMetaDTO;
import com.thisnode.plugin.api.notes.dto.NotebookDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface NotesMapper {

    CardResponseDTO toCardResponseDTO(CardPO card);

    CardMetaDTO toCardMetaDTO(CardPO.CardMeta meta);

    @Mapping(target = "sourceFileName", expression = "java(card.sourceFileName())")
    CardDTO toCardDTO(CardPO card);

    NoteMetaDTO toNoteMetaDTO(NoteMetaPO noteMeta);

    NotebookDTO toNotebookDTO(NotebookPO notebook);

    @Mapping(target = "noteMeta", source = "noteMeta")
    @Mapping(target = "cards", source = "cards")
    NoteDTO toNoteDTO(NoteMetaPO noteMeta, List<CardPO> cards);
}
