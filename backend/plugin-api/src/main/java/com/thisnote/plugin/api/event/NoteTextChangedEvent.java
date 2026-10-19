package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginNotification;

/**
 * Notes text changed for a note, including its title or card bodies.
 */
public record NoteTextChangedEvent(String noteId)
        implements PluginNotification {

    public NoteTextChangedEvent {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        noteId = noteId.trim();
    }
}
