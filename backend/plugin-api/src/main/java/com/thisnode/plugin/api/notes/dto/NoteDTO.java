package com.thisnode.plugin.api.notes.dto;

import java.util.List;

public record NoteDTO(NoteMetaDTO noteMeta, List<CardResponseDTO> cards) {
}
