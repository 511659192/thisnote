package com.thisnote.ext.notes.controller;

import com.thisnote.ext.notes.config.NotesConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/notes/settings")
@RequiredArgsConstructor
public class NotesSettingsController {

    private final NotesConfig notesConfig;

    @GetMapping("/get")
    public Map<String, Object> get() {
        Path root = notesConfig.getRootDir();
        return Map.of("rootDir", root.toString(), "activeRootDir", root.toString(), "restartRequired", false);
    }
}
