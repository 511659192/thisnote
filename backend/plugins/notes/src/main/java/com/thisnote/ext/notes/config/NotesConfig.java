package com.thisnote.ext.notes.config;

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

    private final Path dataRoot;
    private final Path rootDir;

    public NotesConfig(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
        this.rootDir = this.dataRoot.resolve("notes");
    }
}
