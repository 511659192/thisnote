package com.thisnote.ext.tags.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Repository
public class TagsRepository {

    private final FileHelper fileHelper;
    private final Path tagsFile;

    public TagsRepository(FileHelper fileHelper,
                          @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins").resolve("tags");
        this.tagsFile = pluginDir.resolve("tags.json");
    }

    public synchronized Map<String, List<String>> all() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        read().forEach((noteId, tags) -> result.put(noteId, List.copyOf(tags)));
        return result;
    }

    public synchronized void replaceNoteTags(String noteId,
                                             List<String> tags) {
        Map<String, List<String>> stored = read();
        List<String> normalized = normalizeTags(tags);
        if (normalized.isEmpty()) {
            stored.remove(noteId);
        } else {
            stored.put(noteId, normalized);
        }
        write(stored);
    }

    public synchronized int removeEverywhere(String tag) {
        Map<String, List<String>> stored = read();
        int removed = 0;
        for (var iterator = stored.values().iterator(); iterator.hasNext(); ) {
            List<String> noteTags = iterator.next();
            if (!noteTags.remove(tag)) {
                continue;
            }
            removed++;
            if (noteTags.isEmpty()) {
                iterator.remove();
            }
        }
        if (removed > 0) {
            write(stored);
        }
        return removed;
    }

    public synchronized boolean removeNote(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return false;
        }
        Map<String, List<String>> stored = read();
        if (stored.remove(noteId.trim()) == null) {
            return false;
        }
        write(stored);
        return true;
    }

    public synchronized void clear() {
        if (Files.exists(tagsFile)) {
            fileHelper.fileDeleted(tagsFile);
        }
    }

    private Map<String, List<String>> read() {
        if (!Files.exists(tagsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, List<String>> loaded = JSON.parseObject(
                    Files.readString(tagsFile),
                    new TypeReference<LinkedHashMap<String, List<String>>>() { }
            );
            Map<String, List<String>> result = new LinkedHashMap<>();
            if (loaded != null) {
                loaded.forEach((noteId, tags) -> {
                    if (noteId == null || noteId.isBlank()) {
                        return;
                    }
                    List<String> normalized = normalizeTags(tags);
                    if (!normalized.isEmpty()) {
                        result.put(noteId.trim(), normalized);
                    }
                });
            }
            return result;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("标签文件无法读取", e);
        }
    }

    private void write(Map<String, List<String>> tags) {
        if (tags.isEmpty()) {
            if (Files.exists(tagsFile)) {
                fileHelper.fileDeleted(tagsFile);
            }
            return;
        }
        fileHelper.fileWritten(tagsFile, tags);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(
                tags.stream()
                        .filter(tag -> tag != null && !tag.isBlank())
                        .map(String::trim)
                        .toList()
        ));
    }
}
