package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginEvent;

import java.util.ArrayList;
import java.util.List;

public record NotesMovedEvent(List<String> noteIds, String bookId)
        implements PluginEvent {

    public NotesMovedEvent {
        noteIds = noteIds == null ? List.of() : new ArrayList<>(noteIds);
    }
}
