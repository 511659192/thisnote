package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.client.TagsNotesClient;
import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.ext.tags.repository.TagsRepository.NoteUsage;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class TagsService {

    private static final int MAX_TAG_LENGTH = 64;

    private final TagsRepository tagsRepository;
    private final TagsNotesClient notesClient;

    public TagsService(TagsRepository tagsRepository,
                       TagsNotesClient notesClient) {
        this.tagsRepository = tagsRepository;
        this.notesClient = notesClient;
    }

    public Map<String, List<String>> all() {
        return tagsRepository.all();
    }

    public synchronized List<String> add(String noteId,
                                         String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        NoteUsage current = tagsRepository.entries().get(normalizedNoteId);
        List<String> noteTags = new ArrayList<>(current == null ? List.of() : current.tags());
        if (!noteTags.contains(normalizedTag)) {
            noteTags.add(normalizedTag);
        }
        saveWithFreshCounts(normalizedNoteId, noteTags, current);
        return List.copyOf(noteTags);
    }

    public synchronized List<String> remove(String noteId,
                                            String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        NoteUsage current = tagsRepository.entries().get(normalizedNoteId);
        List<String> noteTags = new ArrayList<>(current == null ? List.of() : current.tags());
        if (noteTags.remove(normalizedTag)) {
            Map<String, Integer> occurrences = current == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(current.occurrences());
            occurrences.remove(normalizedTag);
            tagsRepository.replaceNoteTags(
                    normalizedNoteId,
                    noteTags,
                    occurrences,
                    current != null && current.countsInitialized()
            );
        }
        return List.copyOf(noteTags);
    }

    public synchronized List<String> mergeUpTo(String noteId,
                                               List<String> candidates,
                                               int maxTotal,
                                               String noteText) {
        String normalizedNoteId = requireNoteId(noteId);
        NoteUsage current = tagsRepository.entries().get(normalizedNoteId);
        List<String> noteTags = new ArrayList<>(current == null ? List.of() : current.tags());
        for (String candidate : candidates) {
            if (noteTags.size() >= maxTotal) {
                break;
            }
            String tag = requireTag(candidate);
            if (!noteTags.contains(tag)) {
                noteTags.add(tag);
            }
        }
        Map<String, Integer> occurrences = TagTextCounter.counts(noteText, noteTags);
        tagsRepository.replaceNoteTags(normalizedNoteId, noteTags, occurrences, true);
        return List.copyOf(noteTags);
    }

    public int removeEverywhere(String tag) {
        return tagsRepository.removeEverywhere(requireTag(tag));
    }

    public boolean removeNote(String noteId) {
        return tagsRepository.removeNote(noteId);
    }

    public void clear() {
        tagsRepository.clear();
    }

    public Map<String, Integer> usageCounts() {
        Map<String, NoteUsage> entries = tagsRepository.entries();
        Map<String, Integer> counts = new LinkedHashMap<>();
        entries.values().forEach(entry -> entry.tags().forEach(tag -> counts.putIfAbsent(tag, 0)));
        entries.values().forEach(entry ->
                entry.occurrences().forEach((tag, count) -> counts.merge(tag, count, Integer::sum)));
        return counts;
    }

    public boolean hasUninitializedUsage() {
        return tagsRepository.entries().values().stream()
                .anyMatch(entry -> !entry.tags().isEmpty() && !entry.countsInitialized());
    }

    public void invalidateUsage() {
        tagsRepository.invalidateOccurrences();
    }

    public void invalidateUsage(String noteId) {
        tagsRepository.invalidateOccurrences(requireNoteId(noteId));
    }

    public void refreshUninitializedUsage() {
        Map<String, Map<String, Integer>> refreshed = new LinkedHashMap<>();
        Set<String> notesToRemove = new LinkedHashSet<>();
        Map<String, NoteDTO> notesById = new LinkedHashMap<>();
        for (NoteDTO note : notesClient.allNotes()) {
            if (note != null && note.noteId() != null) {
                notesById.put(note.noteId(), note);
            }
        }
        tagsRepository.entries().forEach((noteId, entry) -> {
            if (entry.tags().isEmpty() || entry.countsInitialized()) {
                return;
            }
            NoteDTO note = notesById.get(noteId);
            if (note == null) {
                notesToRemove.add(noteId);
                return;
            }
            List<CardDTO> cards = notesClient.cards(noteId);
            refreshed.put(noteId, TagTextCounter.counts(
                    TagTextCounter.noteText(note, cards),
                    entry.tags()
            ));
        });
        tagsRepository.replaceOccurrencesBatch(refreshed, notesToRemove);
    }

    public void refreshOccurrences(String noteId) {
        String normalizedNoteId = requireNoteId(noteId);
        NoteUsage current = tagsRepository.entries().get(normalizedNoteId);
        if (current == null || current.tags().isEmpty()) {
            return;
        }
        NoteDTO note = notesClient.note(normalizedNoteId);
        if (note == null) {
            tagsRepository.removeNote(normalizedNoteId);
            return;
        }
        List<CardDTO> cards = notesClient.cards(normalizedNoteId);
        tagsRepository.replaceOccurrences(
                normalizedNoteId,
                TagTextCounter.counts(TagTextCounter.noteText(note, cards), current.tags())
        );
    }

    private void saveWithFreshCounts(String noteId,
                                     List<String> noteTags,
                                     NoteUsage current) {
        try {
            Map<String, Integer> occurrences = TagTextCounter.counts(noteText(noteId), noteTags);
            tagsRepository.replaceNoteTags(noteId, noteTags, occurrences, true);
        } catch (RuntimeException e) {
            log.warn("标签计数刷新失败，保留待补算状态：noteId={}, errorType={}",
                    noteId, e.getClass().getSimpleName());
            Map<String, Integer> occurrences = current == null
                    ? Map.of()
                    : current.occurrences();
            tagsRepository.replaceNoteTags(
                    noteId,
                    noteTags,
                    occurrences,
                    false
            );
        }
    }

    private String noteText(String noteId) {
        NoteDTO note = notesClient.note(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在：" + noteId);
        }
        List<CardDTO> cards = notesClient.cards(noteId);
        return TagTextCounter.noteText(note, cards);
    }

    private static String requireNoteId(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        return noteId.trim();
    }

    private static String requireTag(String tag) {
        if (tag == null || tag.isBlank() || tag.trim().length() > MAX_TAG_LENGTH) {
            throw new IllegalArgumentException("标签长度必须为 1 到 64 个字符");
        }
        return tag.trim();
    }
}
