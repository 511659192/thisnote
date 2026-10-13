package com.thisnode.plugin.api;

import java.util.List;

/** A note-node lifecycle event published by the notes plugin. */
public record NoteChangedEvent(String noteId, Kind kind, String title, List<String> cardIds) {

    public NoteChangedEvent {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
    }

    public enum Kind {
        CREATED,
        TITLE_CHANGED,
        DELETED
    }
}
