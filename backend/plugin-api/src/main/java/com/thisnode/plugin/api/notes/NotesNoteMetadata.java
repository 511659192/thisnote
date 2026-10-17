package com.thisnode.plugin.api.notes;

import java.util.List;

/**
 * Note metadata exposed without Notes' on-disk JSON representation.
 */
public record NotesNoteMetadata(String noteId, String title, String notebookId, List<String> cardIds) {

    public NotesNoteMetadata {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
    }
}
