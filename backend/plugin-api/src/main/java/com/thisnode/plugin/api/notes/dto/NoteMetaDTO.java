package com.thisnode.plugin.api.notes.dto;

import java.util.List;

public record NoteMetaDTO(int v,
                          String noteId,
                          String title,
                          String notebookId,
                          List<String> cardIds) {
}
