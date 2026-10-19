package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginNotification;

import java.util.List;

/**
 * A notebook lifecycle event published by the notes plugin.
 *
 * @param movedNoteIds note IDs reassigned to the default notebook when deleted; empty otherwise
 */
public record BookChangedEvent(String bookId, Kind kind, String title, String type, List<String> movedNoteIds)
        implements PluginNotification {

    public BookChangedEvent {
        movedNoteIds = movedNoteIds == null ? List.of() : List.copyOf(movedNoteIds);
    }

    public enum Kind {
        CREATED, UPDATED, DELETED
    }
}
