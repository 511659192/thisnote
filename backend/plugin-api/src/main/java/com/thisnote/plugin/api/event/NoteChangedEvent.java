package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginNotification;

import java.util.List;

/**
 * Cross-plugin note lifecycle event.
 */
public record NoteChangedEvent(String noteId, Kind kind, String title, List<String> cardIds)
        implements PluginNotification {

    public NoteChangedEvent {
        cardIds = cardIds == null ? List.of() : cardIds;
    }

    public enum Kind {
        CREATED, TITLE_CHANGED, TEXT_CHANGED, DELETED
    }
}
