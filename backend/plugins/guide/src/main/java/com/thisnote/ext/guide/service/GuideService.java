package com.thisnote.ext.guide.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GuideService {

    private final Path notesRoot;

    public GuideService(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.notesRoot = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes");
    }

    public GuideEnvironment environment() {
        Path pluginDir = notesRoot.resolve("plugins").resolve("notes");
        boolean done = Files.isDirectory(notesRoot.resolve("cards"))
                && Files.isDirectory(notesRoot.resolve("book"))
                && Files.isDirectory(notesRoot.resolve("attachments"))
                && Files.isRegularFile(notesRoot.resolve("book").resolve(".books.json"))
                && Files.isRegularFile(pluginDir.resolve(".note-metadata.json"))
                && Files.isRegularFile(pluginDir.resolve(".card-metadata.json"))
                && Files.isRegularFile(pluginDir.resolve(".attachment-metadata.json"));
        return new GuideEnvironment(
                done,
                done ? notesRoot.toString() : "数据目录尚未完成初始化：" + notesRoot
        );
    }

    public record GuideEnvironment(boolean done, String detail) {
    }
}
