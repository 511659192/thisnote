package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.MarkdownUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 链接的**读取**：{@code [[标题]]} 从卡片正文现算，没有链接表、没有索引缓存。
 *
 * <p>唯一需要的查询是「谁引用了这篇笔记」——改名跟随要靠它把各篇正文里的 {@code [[旧标题]]} 一起改掉。
 * 改名是低频操作，直接扫各篇正文比养一份随时可能过期的入链索引便宜，也不会出现"索引说有人引用、
 * 正文里其实没有"这种分歧。
 */
@Repository
@RequiredArgsConstructor
public class LinkRepository {

    private final NoteRepository noteRepository;

    /** 正文里以标题形式引用了 {@code noteId} 这篇的其他笔记 id（按清单顺序）。 */
    public List<String> referencingNodeIds(String noteId) {
        NoteMeta note = noteRepository.getOne(noteId);
        if (note == null || note.title().isBlank()) {
            return List.of();
        }
        List<String> variants = MarkdownUtils.wikiLinkVariants(note.title());
        List<String> out = new ArrayList<>();
        for (NoteMeta other : noteRepository.findAll()) {
            if (other.id().equals(noteId)) {
                continue;
            }
            String markdown = noteRepository.noteMarkdown(other.id());
            for (String variant : variants) {
                if (markdown.contains(variant)) {
                    out.add(other.id());
                    break;
                }
            }
        }
        return out;
    }
}
