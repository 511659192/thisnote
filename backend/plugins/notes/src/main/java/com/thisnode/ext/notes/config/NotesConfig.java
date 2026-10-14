package com.thisnode.ext.notes.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Configuration consumed by the notes domain. Note and card writes create their own directories.
 */
@Configuration
@Getter
public class NotesConfig {

    private final Path rootDir;

    private final Path noteMetaDir;

    private final Path cardsDir;

    private final Path notebookDir;

    private final Path attachmentsDir;

    public NotesConfig(NotesRootSettingsRepository rootSettingsRepository,
                      @Value("${thisnode.root-dir}") Path defaultRootDir) {
        this.rootDir = rootSettingsRepository.configuredRoot(defaultRootDir);
        this.noteMetaDir = rootDir.resolve("meta");
        this.cardsDir = rootDir.resolve("cards");
        this.notebookDir = rootDir.resolve("notebook");
        this.attachmentsDir = rootDir.resolve("attachments");
    }
}
