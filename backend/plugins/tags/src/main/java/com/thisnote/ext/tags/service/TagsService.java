package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.ext.tags.repository.TagsRepository.NoteUsage;
import com.thisnote.ext.tags.support.TagTextCounter;
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
import java.util.stream.Collectors;

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

    public List<String> noteTags(String noteId) {
        NoteUsage current = tagsRepository.getAllEntries().get(requireNoteId(noteId));
        return current == null ? List.of() : current.tags();
    }

    public synchronized List<String> add(String noteId,
                                         String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        NoteUsage current = tagsRepository.getAllEntries().get(normalizedNoteId);
        List<String> noteTags = new ArrayList<>(current == null ? List.of() : current.tags());
        boolean added = !noteTags.contains(normalizedTag);
        if (added) {
            noteTags.add(normalizedTag);
        }
        saveWithFreshCounts(normalizedNoteId, noteTags, current);
        if (added) {
            log.info("标签已添加：noteId={}, tag={}", normalizedNoteId, normalizedTag);
        }
        return noteTags;
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
            log.info("标签已移除：noteId={}, tag={}", normalizedNoteId, normalizedTag);
        }
        return noteTags;
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
        log.debug("标签合并完成：noteId={}, candidates={}, total={}",
                normalizedNoteId, candidates.size(), noteTags.size());
        return noteTags;
    }

    public int removeEverywhere(String tag) {
        String normalizedTag = requireTag(tag);
        int removedNotes = tagsRepository.deleteTagEverywhere(normalizedTag);
        log.info("标签全局删除：tag={}, removedNotes={}", normalizedTag, removedNotes);
        return removedNotes;
    }

    public Map<String, Integer> usageCounts() {
        Map<String, NoteUsage> entries = tagsRepository.getAllEntries();
        Map<String, Integer> counts = new LinkedHashMap<>();
        entries.values().forEach(entry -> entry.tags().forEach(tag -> counts.putIfAbsent(tag, 0)));
        entries.values().forEach(entry ->
                entry.occurrences().forEach((tag, count) -> counts.merge(tag, count, Integer::sum)));
        return counts;
    }

    public Map<String, Integer> occurrences() {
        try {
            if (hasUninitializedUsage()) {
                refreshUninitializedUsage();
            }
            return usageCounts();
        } catch (Exception e) {
            log.error("标签计数缓存补算失败，回退实时统计", e);
            return calculateRealtime(usageCounts());
        }
    }

    public boolean hasUninitializedUsage() {
        return tagsRepository.getAllEntries().values().stream()
                .anyMatch(entry -> !entry.tags().isEmpty() && !entry.countsInitialized());
    }

    public void invalidateUsage() {
        tagsRepository.updateAllCountsInitialized(false);
    }

    public void invalidateUsage(String noteId) {
        tagsRepository.updateCountsInitialized(requireNoteId(noteId), false);
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
            NoteCardDTO content = noteContent(noteId);
            List<CardDTO> cards = content == null ? List.of() : content.cards();
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
        NoteCardDTO content = noteContent(normalizedNoteId);
        if (content == null || content.note() == null) {
            tagsRepository.deleteNote(normalizedNoteId);
            return;
        }
        tagsRepository.updateOccurrences(
                normalizedNoteId,
                TagTextCounter.counts(TagTextCounter.noteText(content.note(), content.cards()), current.tags())
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

    private Map<String, Integer> calculateRealtime(Map<String, Integer> knownTags) {
        Set<String> tags = new LinkedHashSet<>(knownTags.keySet());
        if (tags.isEmpty()) {
            return knownTags;
        }
        String corpus = allNotes().stream()
                .filter(note -> note != null && note.noteId() != null && !note.noteId().isBlank())
                .map(note -> {
                    NoteCardDTO content = noteContent(note.noteId());
                    return TagTextCounter.noteText(note, content == null ? List.of() : content.cards()) + "\n";
                })
                .collect(Collectors.joining());
        Map<String, Integer> result = new LinkedHashMap<>(knownTags);
        result.putAll(TagTextCounter.counts(corpus, tags));
        return result;
    }

    private String noteText(String noteId) {
        NoteCardDTO content = noteContent(noteId);
        if (content == null || content.note() == null) {
            throw new IllegalArgumentException("笔记不存在：" + noteId);
        }
        return TagTextCounter.noteText(content.note(), content.cards());
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
