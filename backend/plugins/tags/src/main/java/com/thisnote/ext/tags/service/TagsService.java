package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.repository.TagsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TagsService {

    private static final int MAX_TAG_LENGTH = 64;

    private final TagsRepository tagsRepository;

    public TagsService(TagsRepository tagsRepository) {
        this.tagsRepository = tagsRepository;
    }

    public Map<String, List<String>> all() {
        return tagsRepository.all();
    }

    public synchronized List<String> add(String noteId,
                                         String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        List<String> noteTags = new ArrayList<>(
                tagsRepository.all().getOrDefault(normalizedNoteId, List.of()));
        if (!noteTags.contains(normalizedTag)) {
            noteTags.add(normalizedTag);
            tagsRepository.replaceNoteTags(normalizedNoteId, noteTags);
        }
        return List.copyOf(noteTags);
    }

    public synchronized List<String> remove(String noteId,
                                            String tag) {
        String normalizedNoteId = requireNoteId(noteId);
        String normalizedTag = requireTag(tag);
        List<String> noteTags = new ArrayList<>(
                tagsRepository.all().getOrDefault(normalizedNoteId, List.of()));
        if (noteTags.remove(normalizedTag)) {
            tagsRepository.replaceNoteTags(normalizedNoteId, noteTags);
        }
        return List.copyOf(noteTags);
    }

    public synchronized List<String> mergeUpTo(String noteId,
                                               List<String> candidates,
                                               int maxTotal) {
        String normalizedNoteId = requireNoteId(noteId);
        List<String> noteTags = new ArrayList<>(
                tagsRepository.all().getOrDefault(normalizedNoteId, List.of()));
        for (String candidate : candidates) {
            if (noteTags.size() >= maxTotal) {
                break;
            }
            String tag = requireTag(candidate);
            if (!noteTags.contains(tag)) {
                noteTags.add(tag);
            }
        }
        tagsRepository.replaceNoteTags(normalizedNoteId, noteTags);
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
