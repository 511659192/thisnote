package com.thisnote.ext.baidusync.service;

import com.thisnote.plugin.api.meta.PluginMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupScopeTest {

    @Test
    void includesNotesAndDeclaredPluginDataOnly() {
        BackupScope scope = new BackupScope(Path.of("notes"), Map.of(
                "notes", metadata(true, List.of()),
                "ai", metadata(true, List.of(".credentials.json")),
                "tags", metadata(true, List.of()),
                "baidu-sync", metadata(false, List.of())
        ));

        assertTrue(scope.includes("cards/a.md"));
        assertTrue(scope.includes("attachments/a.png"));
        assertTrue(scope.includes("plugins/notes/.note-metadata.json"));
        assertTrue(scope.includes("plugins/tags/tags.json"));
        assertTrue(scope.includes("plugins/ai/settings.json"));

        assertFalse(scope.includes("plugins/ai/.credentials.json"));
        assertFalse(scope.includes("plugins/baidu-sync/.sync-state.json"));
        assertFalse(scope.includes("plugins/unknown/secret.txt"));
        assertFalse(scope.includes("plugins/tags/../ai/.credentials.json"));
        assertFalse(scope.includes("../outside.txt"));
    }

    @Test
    void traversesOnlyAncestorsOfIncludedRoots() {
        BackupScope scope = new BackupScope(Path.of("notes"), Map.of(
                "tags", metadata(true, List.of())
        ));

        assertTrue(scope.mayContain("plugins"));
        assertTrue(scope.mayContain("plugins/tags"));
        assertFalse(scope.mayContain("plugins/unknown"));
        assertFalse(scope.mayContain("other"));
    }

    private static PluginMetadata metadata(boolean backupEnabled,
                                           List<String> backupExclude) {
        return new PluginMetadata("title", "description", false, List.of(), backupEnabled, backupExclude);
    }
}
