package com.thisnode.plugin.api.event;

import java.util.List;

/** Cross-plugin note lifecycle event. */
public record NoteChangedEvent(String noteId,
                               Kind kind,
                               String title,
                               List<String> cardIds) {

    public NoteChangedEvent {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
    }

    public enum Kind {
        CREATED,
        TITLE_CHANGED,
        DELETED
    }
}
