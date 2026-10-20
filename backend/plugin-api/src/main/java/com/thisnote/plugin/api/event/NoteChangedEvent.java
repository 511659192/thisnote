package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginEvent;

import java.util.List;

/**
 * Cross-plugin note lifecycle event.
 */
public record NoteChangedEvent(String noteId, Kind kind, String title, List<String> cardIds)
        implements PluginEvent {

    public NoteChangedEvent {
        cardIds = cardIds == null ? List.of() : cardIds;
    }

    public enum Kind {
        CREATED, TITLE_CHANGED, TEXT_CHANGED, DELETED
    }
}
