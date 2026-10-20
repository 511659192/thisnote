package com.thisnote.ext.baidusync.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupScopeTest {

    @Test
    void includesNotesAndInstalledPluginData() {
        BackupScope scope = new BackupScope(Path.of("notes"), Set.of("notes", "ai", "tags", "baidu-sync"));

        assertTrue(scope.includes("cards/a.md"));
        assertTrue(scope.includes("attachments/a.png"));
        assertTrue(scope.includes("plugins/notes/.note-metadata.json"));
        assertTrue(scope.includes("plugins/tags/tags.json"));
        assertTrue(scope.includes("plugins/ai/settings.json"));

        assertTrue(scope.includes("plugins/ai/.credentials.json"));
        assertTrue(scope.includes("plugins/baidu-sync/.sync-state.json"));
        assertFalse(scope.includes("plugins/unknown/secret.txt"));
        assertFalse(scope.includes("plugins/tags/../ai/.credentials.json"));
        assertFalse(scope.includes("../outside.txt"));
    }

    @Test
    void traversesOnlyAncestorsOfIncludedRoots() {
        BackupScope scope = new BackupScope(Path.of("notes"), Set.of("tags"));

        assertTrue(scope.mayContain("plugins"));
        assertTrue(scope.mayContain("plugins/tags"));
        assertFalse(scope.mayContain("plugins/unknown"));
        assertFalse(scope.mayContain("other"));
    }

}
