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

    private final Path cardsDir;

    private final Path bookDir;

    private final Path attachmentsDir;

    /** 笔记插件自己的数据目录：索引文件放在这里，不再散落在笔记根目录。 */
    private final Path notesPluginDir;

    public NotesConfig(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.rootDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes");
        this.cardsDir = rootDir.resolve("cards");
        this.bookDir = rootDir.resolve("book");
        this.attachmentsDir = rootDir.resolve("attachments");
        this.notesPluginDir = rootDir.resolve("plugins").resolve("notes");
    }
}
