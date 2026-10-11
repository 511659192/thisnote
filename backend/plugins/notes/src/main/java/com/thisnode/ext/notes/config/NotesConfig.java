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

    @Value("${thisnode.root-dir}")
    private Path rootDir;

    @Value("${thisnode.note-meta-dir}")
    private Path noteMetaDir;

    @Value("${thisnode.cards-dir}")
    private Path cardsDir;

    @Value("${thisnode.notebook-dir}")
    private Path notebookDir;
}
