package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteBlockRepository;
import com.thisnode.ext.notes.domain.Note;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.domain.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A note's page: the cards it is made of.
 *
 * <p><b>Model.</b> A note *is* the page. Each card owns a fragment of the note's content
 * ({@code note_blocks.content_md}) plus its position; the note's markdown is the join of its
 * cards in z order. So there is exactly one copy of the content, and a card's text never
 * shifts when a neighbouring card grows — which is what makes "write in the card" safe.
 *
 * <p>Card content is authoritative. The note's markdown is a derived projection, kept in sync
 * on every write so search, links, the Markdown mirror and export all keep working unchanged.
 */
@Service
@RequiredArgsConstructor
public class NoteBlockService {

    public static final String DEFAULT_COLOR = "#3b82f6";

    private final NoteBlockRepository blockRepository;
    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final TagRepository tagRepository;

    /** Cap on cards per note; keeps a page inside the interaction budget it is measured for. */
    @Value("${thisnode.max-blocks-per-note:100}")
    private int maxBlocksPerNote;

    public int maxBlocksPerNote() {
        return Math.max(1, maxBlocksPerNote);
    }

    /** Thrown when a page already holds the maximum number of cards. */
    public static class BlockLimitException extends com.thisnode.plugin.api.BlockLimitException {
        public BlockLimitException(int limit) {
            super("每篇笔记最多 " + limit + " 张卡片。请新建一篇笔记，或先合并/删除部分卡片。", limit);
        }
    }

    // ---------------------------------------------------------------- read

    /**
     * 一篇笔记的卡片：{@code ids} 为空表示整页，给了就只回这几张。
     *
     * <p>这是「按需加载」的入口：列表页只带卡片清单（不读卡片文件），打开笔记后按视口分批
     * 用 {@code ids} 来取——几何与正文一起给，因为它们同在一次读取里（都得打开卡片文件）。
     *
     * <p>空页面不再在这里补建卡片：读路径不该写盘。不变量「笔记至少有一张卡」由
     * {@code NoteService.create} 在新建笔记时建立。
     */
    public Dtos.NoteBlocksDto page(String noteId, List<String> ids) {
        Note note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<Dtos.NoteBlockDto> rows = blockRepository.blocks(noteId);
        if (wanted != null) {
            rows = rows.stream().filter(b -> wanted.contains(b.id())).toList();
        }
        List<Dtos.TagDto> noteTags = tagRepository.tagsOf(noteId);
        List<Dtos.NoteBlockView> views = new ArrayList<>(rows.size());
        for (Dtos.NoteBlockDto b : rows) {
            views.add(new Dtos.NoteBlockView(b, b.contentMd(), note.title, noteTags));
        }
        return new Dtos.NoteBlocksDto(note.id, note.title, views, noteTags,
                maxBlocksPerNote(), note.updatedAt);
    }

    // ---------------------------------------------------------------- write

    private void requireCapacity(String noteId, int adding) {
        int limit = maxBlocksPerNote();
        if (blockRepository.count(noteId) + adding > limit) {
            throw new BlockLimitException(limit);
        }
    }

    /** Add an empty (or seeded) card to a note's page. */
    public Dtos.NoteBlockView create(String noteId, Dtos.CreateBlockRequest req) {
        Note note = noteService.require(noteId);
        requireCapacity(noteId, 1);
        String content = req == null || req.contentMd() == null ? "" : req.contentMd();
        String title = req == null || req.title() == null ? "" : req.title().trim();
        // The caller's point is honoured: a double-click places the card where the user aimed.
        // Callers that have no point to give (the Markdown importer, the demo seeder's fallback)
        // still get appended below the column, so a card is never dropped on top of another.
        double[] flow = blockRepository.nextFlowPosition(noteId);
        double x = req != null && req.x() != null ? Math.max(0, req.x()) : flow[0];
        double y = req != null && req.y() != null ? Math.max(0, req.y()) : flow[1];
        String id = blockRepository.createAt(noteId, x, y,
                req == null ? null : req.w(), req == null ? null : req.h(),
                req == null ? null : req.color(), null, content, title);
        syncNoteFromBlocks(note);
        Note fresh = noteService.require(noteId);
        Dtos.NoteBlockDto stored = blockRepository.find(id);
        return new Dtos.NoteBlockView(stored, stored.contentMd(), fresh.title,
                tagRepository.tagsOf(noteId));
    }

    /**
     * Write markdown typed inside a card. The card owns this text; the note follows.
     *
     * <p>{@code baseContentMd} 是客户端编辑时所依据的正文：磁盘上已经不是那一份就拒写（409），
     * 免得把外部编辑器改过的内容覆盖掉。
     */
    public Dtos.NoteBlockView writeContent(String blockId, String contentMd, String baseContentMd) {
        Dtos.NoteBlockDto block = requireBlock(blockId);
        blockRepository.setContent(blockId, contentMd, baseContentMd);
        Note note = noteService.require(block.noteId());
        syncNoteFromBlocks(note);
        Note fresh = noteService.require(note.id);
        return new Dtos.NoteBlockView(blockRepository.find(blockId),
                contentMd == null ? "" : contentMd, fresh.title, tagRepository.tagsOf(note.id));
    }

    /**
     * Rebuild the note's markdown from its cards (z order), so search, links, the Markdown
     * mirror and JSON export all see the text that was typed into the cards.
     */
    private void syncNoteFromBlocks(Note note) {
        List<String> parts = blockRepository.contentsInOrder(note.id);
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            joined.append(part.strip());
        }
        String next = joined.toString();
        String current = note.contentMd == null ? "" : note.contentMd;
        if (!next.equals(current)) {
            noteService.patch(note.id, new Dtos.UpdateNodeRequest(
                    null, next, null, null, null, null, null, null));
        }
    }

    private Dtos.NoteBlockDto requireBlock(String id) {
        Dtos.NoteBlockDto block = blockRepository.find(id);
        if (block == null) {
            throw new NoteService.NotFoundException("block " + id);
        }
        return block;
    }

    public Dtos.NoteBlockDto update(String blockId, Dtos.BlockPatch patch) {
        requireBlock(blockId);
        blockRepository.update(blockId, patch.title(), patch.x(), patch.y(), patch.w(), patch.h(),
                patch.z(), patch.color(), patch.status(), patch.collapsed());
        // Resizing a card leaves its neighbours exactly where they are: cards are positioned
        // independently, so there is no column to re-close.
        return blockRepository.find(blockId);
    }

    /** Batched geometry write for drag / resize / distribute. */
    public void batchUpdate(Dtos.BatchBlockPatchRequest req) {
        List<Dtos.BlockPatch> patches = req == null || req.blocks() == null ? List.of() : req.blocks();
        if (patches.isEmpty()) {
            return;
        }
        blockRepository.updateBatch(patches);
    }

    /**
     * Duplicate a card into a brand-new note (so the copy can diverge from the original).
     * The new note starts with the card's text.
     */
    public Dtos.NoteBlockDto duplicate(String blockId, double dx, double dy) {
        Dtos.NoteBlockDto src = requireBlock(blockId);
        String newNoteId = noteService.create(new Dtos.CreateNodeRequest(
                null, src.contentMd() == null ? "" : src.contentMd(), "note",
                null, null, null, null, null, null));
        String id = blockRepository.create(newNoteId, 0d, 0d, src.w(), src.h(), src.color(), src.status(),
                src.contentMd() == null ? "" : src.contentMd(), src.title());
        noteService.log("note-block", id, "duplicate-from",
                Map.of("fromBlock", blockId, "fromNote", src.noteId()));
        return blockRepository.find(id);
    }

    /**
     * Delete a card. {@code deleteContent} also removes the note when the card was its only
     * content; otherwise the note's markdown is rebuilt from the remaining cards.
     */
    public void delete(String blockId, boolean deleteContent) {
        Dtos.NoteBlockDto block = requireBlock(blockId);
        Note note = noteRepository.findById(block.noteId());
        if (blockRepository.count(block.noteId()) <= 1) {
            if (deleteContent) {
                noteService.trash(block.noteId());
                return;
            }
            // Keep the page non-empty: clear the last card rather than leaving nothing.
            blockRepository.setContent(blockId, "");
            if (note != null) {
                syncNoteFromBlocks(note);
            }
            return;
        }
        blockRepository.delete(blockId);
        if (note != null) {
            // The gap the card leaves behind stays open: the remaining cards keep the positions
            // the user gave them.
            syncNoteFromBlocks(note);
        }
        noteService.log("note-block", blockId, "delete",
                Map.of("noteId", block.noteId(), "deleteContent", deleteContent));
    }

    /** z-order: bring a card to the front or send it back. */
    public Dtos.NoteBlockDto zOrder(String blockId, String op) {
        Dtos.NoteBlockDto block = requireBlock(blockId);
        Integer z = switch (op == null ? "" : op) {
            case "top" -> blockRepository.nextZ(block.noteId());
            case "bottom" -> blockRepository.lowestZ(block.noteId());
            case "up" -> block.z() + 1;
            case "down" -> block.z() - 1;
            default -> block.z();
        };
        blockRepository.update(blockId, null, null, null, null, null, z, null, null, null);
        return blockRepository.find(blockId);
    }

}
