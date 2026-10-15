package com.thisnode.plugin.api.event;

import java.util.List;

/**
 * A notebook lifecycle event published by the notes plugin.
 *
 * @param movedNoteIds note IDs reassigned to the default notebook when deleted; empty otherwise
 */
public record NotebookChangedEvent(String notebookId,
                                   Kind kind,
                                   String title,
                                   String type,
                                   List<String> movedNoteIds) {

    public NotebookChangedEvent {
        movedNoteIds = movedNoteIds == null ? List.of() : List.copyOf(movedNoteIds);
    }

    public enum Kind {
        CREATED,
        UPDATED,
        DELETED
    }
}
