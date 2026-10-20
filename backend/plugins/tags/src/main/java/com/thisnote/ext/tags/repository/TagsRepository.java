package com.thisnote.ext.tags.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
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

    public synchronized Map<String, List<String>> getAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        read().forEach((noteId, entry) -> result.put(noteId, entry.tags()));
        return result;
    }

    public synchronized Map<String, NoteUsage> getAllEntries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(read()));
    }

    public synchronized void updateNoteTags(String noteId,
                                            List<String> tags,
                                            Map<String, Integer> occurrences,
                                            boolean countsInitialized) {
        Map<String, NoteUsage> stored = read();
        List<String> normalizedTags = normalizeTags(tags);
        if (normalizedTags.isEmpty()) {
            stored.remove(noteId);
        } else {
            stored.put(noteId, new NoteUsage(normalizedTags, occurrences, countsInitialized));
        }
        write(stored);
    }

    public synchronized void updateOccurrences(String noteId,
                                               Map<String, Integer> occurrences) {
        Map<String, NoteUsage> stored = read();
        NoteUsage current = stored.get(noteId);
        if (current == null) {
            return;
        }
        stored.put(noteId, new NoteUsage(current.tags(), occurrences, true));
        write(stored);
    }

    public synchronized void updateOccurrencesBatch(Map<String, Map<String, Integer>> refreshed,
                                                    Set<String> notesToRemove) {
        Map<String, NoteUsage> stored = read();
        boolean changed = false;
        for (Map.Entry<String, Map<String, Integer>> item : refreshed.entrySet()) {
            NoteUsage current = stored.get(item.getKey());
            if (current == null) {
                continue;
            }
            stored.put(item.getKey(), new NoteUsage(current.tags(), item.getValue(), true));
            changed = true;
        }
        for (String noteId : notesToRemove) {
            changed |= stored.remove(noteId) != null;
        }
        if (changed) {
            write(stored);
        }
    }

    public synchronized void invalidateOccurrences() {
        Map<String, NoteUsage> stored = read();
        boolean changed = false;
        for (Map.Entry<String, NoteUsage> entry : stored.entrySet()) {
            NoteUsage current = entry.getValue();
            if (current.countsInitialized()) {
                entry.setValue(new NoteUsage(current.tags(), current.occurrences(), false));
                changed = true;
            }
        }
        if (changed) {
            write(stored);
        }
    }

    public synchronized void invalidateOccurrences(String noteId) {
        Map<String, NoteUsage> stored = read();
        NoteUsage current = stored.get(noteId);
        if (current == null || !current.countsInitialized()) {
            return;
        }
        stored.put(noteId, new NoteUsage(current.tags(), current.occurrences(), false));
        write(stored);
    }

    public synchronized int deleteTagEverywhere(String tag) {
        Map<String, NoteUsage> stored = read();
        int removed = 0;
        for (var iterator = stored.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, NoteUsage> item = iterator.next();
            NoteUsage current = item.getValue();
            List<String> noteTags = new ArrayList<>(current.tags());
            if (!noteTags.remove(tag)) {
                continue;
            }
            removed++;
            if (noteTags.isEmpty()) {
                iterator.remove();
                continue;
            }
            Map<String, Integer> occurrences = new LinkedHashMap<>(current.occurrences());
            occurrences.remove(tag);
            item.setValue(new NoteUsage(noteTags, occurrences, current.countsInitialized()));
        }
        if (removed > 0) {
            write(stored);
        }
        return removed;
    }

    public synchronized boolean deleteNote(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return false;
        }
        Map<String, NoteUsage> stored = read();
        if (stored.remove(noteId.trim()) == null) {
            return false;
        }
        write(stored);
        return true;
    }

    public synchronized void deleteAll() {
        if (Files.exists(tagsFile)) {
            if (!fileHelper.fileDeleted(tagsFile) && Files.exists(tagsFile)) {
                log.error("删除标签文件失败：{}", tagsFile);
            }
        }
    }

    private Map<String, NoteUsage> read() {
        if (!Files.exists(tagsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject raw = JSON.parseObject(Files.readString(tagsFile));
            if (raw == null) {
                return new LinkedHashMap<>();
            }
            Map<String, NoteUsage> result = readCurrent(raw);
            result.entrySet().removeIf(entry -> entry.getValue().tags().isEmpty());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("标签文件无法读取", e);
        }
    }

    private static Map<String, NoteUsage> readCurrent(JSONObject notes) {
        Map<String, NoteUsage> result = new LinkedHashMap<>();
        notes.forEach((noteId, value) -> {
            if (noteId == null || noteId.isBlank() || !(value instanceof JSONObject note)) {
                return;
            }
            JSONArray tagArray = note.getJSONArray("tags");
            List<String> tags = tagArray == null ? List.of() : tagArray.toJavaList(String.class);
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            JSONObject storedCounts = note.getJSONObject("occurrences");
            if (storedCounts != null) {
                storedCounts.forEach((tag, count) -> occurrences.put(String.valueOf(tag), ((Number) count).intValue()));
            }
            result.put(noteId.trim(), new NoteUsage(
                    tags,
                    occurrences,
                    Boolean.TRUE.equals(note.getBoolean("countsInitialized"))
            ));
        });
        return result;
    }

    private void write(Map<String, NoteUsage> tags) {
        if (tags.isEmpty()) {
            if (Files.exists(tagsFile)) {
                if (!fileHelper.fileDeleted(tagsFile) && Files.exists(tagsFile)) {
                    log.error("删除标签文件失败：{}", tagsFile);
                }
            }
            return;
        }
        JSONObject tagsJson = new JSONObject();
        tags.forEach((noteId, usage) -> {
            JSONObject note = new JSONObject();
            note.put("tags", usage.tags());
            note.put("occurrences", usage.occurrences());
            note.put("countsInitialized", usage.countsInitialized());
            tagsJson.put(noteId, note);
        });
        fileHelper.fileWritten(tagsFile, tagsJson);
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

    private static Map<String, Integer> normalizeOccurrences(List<String> tags,
                                                             Map<String, Integer> occurrences) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (occurrences == null) {
            return result;
        }
        for (String tag : tags) {
            Integer count = occurrences.get(tag);
            if (count != null && count > 0) {
                result.put(tag, count);
            }
        }
        return result;
    }

    public record NoteUsage(List<String> tags,
                            Map<String, Integer> occurrences,
                            boolean countsInitialized) {

        public NoteUsage {
            tags = normalizeTags(tags);
            occurrences = Collections.unmodifiableMap(normalizeOccurrences(tags, occurrences));
        }
    }
}
