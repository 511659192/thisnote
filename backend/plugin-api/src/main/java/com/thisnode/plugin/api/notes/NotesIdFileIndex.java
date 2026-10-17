package com.thisnode.plugin.api.notes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of Notes' stable-ID to physical-file mappings.
 */
public record NotesIdFileIndex(Map<String, String> noteFiles, Map<String, String> cardFiles) {

    public NotesIdFileIndex {
        noteFiles = noteFiles == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(noteFiles));
        cardFiles = cardFiles == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(cardFiles));
    }
}
