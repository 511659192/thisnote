package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.ext.tags.repository.TagsRepository.NoteUsage;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.event.NoteListFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TagsService {

    private static final int MAX_TAG_LENGTH = 64;
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final TagsRepository tagsRepository;
    private ApplicationEventPublisher eventPublisher;

    public TagsService(TagsRepository tagsRepository) {
        this.tagsRepository = tagsRepository;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public Map<String, List<String>> all() {
        return tagsRepository.getAll();
    }

    public synchronized List<String> add(String noteId,
                                         String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        NoteUsage current = tagsRepository.getAllEntries().get(normalizedNoteId);
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
        NoteUsage current = tagsRepository.getAllEntries().get(normalizedNoteId);
        List<String> noteTags = new ArrayList<>(current == null ? List.of() : current.tags());
        if (noteTags.remove(normalizedTag)) {
            Map<String, Integer> occurrences = current == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(current.occurrences());
            occurrences.remove(normalizedTag);
            tagsRepository.updateNoteTags(
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
        NoteUsage current = tagsRepository.getAllEntries().get(normalizedNoteId);
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
        tagsRepository.updateNoteTags(normalizedNoteId, noteTags, occurrences, true);
        return List.copyOf(noteTags);
    }

    public int removeEverywhere(String tag) {
        return tagsRepository.deleteTagEverywhere(requireTag(tag));
    }

    public Map<String, Integer> usageCounts() {
        Map<String, NoteUsage> entries = tagsRepository.getAllEntries();
        Map<String, Integer> counts = new LinkedHashMap<>();
        entries.values().forEach(entry -> entry.tags().forEach(tag -> counts.putIfAbsent(tag, 0)));
        entries.values().forEach(entry ->
                entry.occurrences().forEach((tag, count) -> counts.merge(tag, count, Integer::sum)));
        return counts;
    }

    public boolean hasUninitializedUsage() {
        return tagsRepository.getAllEntries().values().stream()
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
        for (NoteDTO note : allNotes()) {
            if (note != null && note.noteId() != null) {
                notesById.put(note.noteId(), note);
            }
        }
        tagsRepository.getAllEntries().forEach((noteId, entry) -> {
            if (entry.tags().isEmpty() || entry.countsInitialized()) {
                return;
            }
            NoteDTO note = notesById.get(noteId);
            if (note == null) {
                notesToRemove.add(noteId);
                return;
            }
            List<CardDTO> cards = cards(noteId);
            refreshed.put(noteId, TagTextCounter.counts(
                    TagTextCounter.noteText(note, cards),
                    entry.tags()
            ));
        });
        tagsRepository.updateOccurrencesBatch(refreshed, notesToRemove);
    }

    public void refreshOccurrences(String noteId) {
        String normalizedNoteId = requireNoteId(noteId);
        NoteUsage current = tagsRepository.getAllEntries().get(normalizedNoteId);
        if (current == null || current.tags().isEmpty()) {
            return;
        }
        NoteDTO note = note(normalizedNoteId);
        if (note == null) {
            tagsRepository.deleteNote(normalizedNoteId);
            return;
        }
        List<CardDTO> cards = cards(normalizedNoteId);
        tagsRepository.updateOccurrences(
                normalizedNoteId,
                TagTextCounter.counts(TagTextCounter.noteText(note, cards), current.tags())
        );
    }

    private void saveWithFreshCounts(String noteId,
                                     List<String> noteTags,
                                     NoteUsage current) {
        try {
            Map<String, Integer> occurrences = TagTextCounter.counts(noteText(noteId), noteTags);
            tagsRepository.updateNoteTags(noteId, noteTags, occurrences, true);
        } catch (Exception e) {
            log.error("标签计数刷新失败，保留待补算状态：noteId={}", noteId, e);
            Map<String, Integer> occurrences = current == null
                    ? Map.of()
                    : current.occurrences();
            tagsRepository.updateNoteTags(
                    noteId,
                    noteTags,
                    occurrences,
                    false
            );
        }
    }

    private String noteText(String noteId) {
        NoteDTO note = note(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在：" + noteId);
        }
        List<CardDTO> cards = cards(noteId);
        return TagTextCounter.noteText(note, cards);
    }

    public NoteDTO note(String noteId) {
        NoteCardDTO content = noteContent(noteId);
        return content == null ? null : content.note();
    }

    public List<CardDTO> cards(String noteId) {
        NoteCardDTO content = noteContent(noteId);
        return content == null ? List.of() : content.cards();
    }

    public List<NoteDTO> allNotes() {
        CompletableFuture<List<NoteDTO>> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteListFutureEvent(result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    public NoteCardDTO noteContent(String noteId) {
        CompletableFuture<NoteCardDTO> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteContentFutureEvent(noteId, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("Notes 消息管道尚未就绪");
        }
        return eventPublisher;
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
