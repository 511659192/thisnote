package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.config.NotesRootSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/notes/settings")
@RequiredArgsConstructor
public class NotesSettingsController {

    private final NotesConfig notesConfig;
    private final NotesRootSettingsRepository rootSettingsRepository;

    @GetMapping("/get")
    public Map<String, Object> get() {
        return settingsView(rootSettingsRepository.configuredRoot(notesConfig.getRootDir()));
    }

    @PostMapping("/update-root")
    public Map<String, Object> updateRoot(@RequestParam String rootDir) {
        return settingsView(rootSettingsRepository.updateRoot(rootDir));
    }

    private Map<String, Object> settingsView(Path configuredRoot) {
        Path activeRoot = notesConfig.getRootDir();
        return Map.of("rootDir", configuredRoot.toString(),
                      "activeRootDir", activeRoot.toString(),
                      "restartRequired", !configuredRoot.equals(activeRoot));
    }
}
