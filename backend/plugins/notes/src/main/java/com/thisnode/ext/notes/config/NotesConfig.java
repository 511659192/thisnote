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

    public NotesConfig(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.rootDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes");
    }
}
