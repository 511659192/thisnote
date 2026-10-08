package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Note;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.domain.TagRepository;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.store.NoteMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;

    @GetMapping
    public List<NoteMeta> list(
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String notebookId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteService.Query(filter, tag, notebookId, type, text,
                includeArchived, false, limit, offset));
    }

    @GetMapping("/counts")
    public Map<String, Integer> counts() {
        return noteService.counts();
    }

    @PostMapping
    public Dtos.NoteDetail create(@RequestBody Dtos.CreateNodeRequest req) {
        return noteService.detail(noteService.create(req));
    }

    @GetMapping("/{id}")
    public Dtos.NoteDetail detail(@PathVariable String id) {
        return noteService.detail(id);
    }

    /**
     * 改笔记级字段（标题 / 标签 / 别名 / 来源 / 归档…）。
     *
     * <p><b>正文不在这里改</b>：带 {@code contentMd} 的整篇改写会把全文写进第一张卡、同时清空
     * 其余卡片的正文（{@code NoteCompositionService#replaceNoteContent}），是一次静默的数据丢失。正文一律走
     * 卡片端点 {@code PATCH /api/notes/{noteId}/blocks/{blockId}/content}（那边带冲突保护）。
     */
    @PatchMapping("/{id}")
    public Dtos.NoteDetail patch(@PathVariable String id, @RequestBody Dtos.UpdateNodeRequest req) {
        if (req != null && req.contentMd() != null) {
            throw new IllegalArgumentException(
                    "整篇正文改写已停用：请在卡片里编辑（PATCH /api/notes/{noteId}/blocks/{blockId}/content）");
        }
        noteService.patch(id, req);
        return noteService.detail(id);
    }

    @PostMapping("/{id}/duplicate")
    public Dtos.NoteDetail duplicate(@PathVariable String id) {
        return noteService.duplicate(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> trash(@PathVariable String id,
                                     @RequestParam(defaultValue = "false") boolean hard) {
        if (hard) {
            noteService.purge(id);
        } else {
            noteService.trash(id);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id) {
        noteService.restore(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/trash")
    public Map<String, Object> emptyTrash() {
        return Map.of("deleted", noteService.emptyTrash());
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id, @RequestParam boolean value) {
        Note n = noteService.require(id);
        n.archived = value;
        noteRepository.update(n);
        return Map.of("archived", value);
    }

    // ---------------- tags ----------------

    @PostMapping("/{id}/tags")
    public List<Dtos.TagDto> addTag(@PathVariable String id, @RequestBody Map<String, String> body) {
        tagRepository.addNodeTag(id, body.getOrDefault("name", ""));
        noteService.require(id);
        return tagRepository.tagsOf(id);
    }

    @DeleteMapping("/{id}/tags/{name}")
    public List<Dtos.TagDto> removeTag(@PathVariable String id, @PathVariable String name) {
        tagRepository.removeNodeTag(id, name);
        return tagRepository.tagsOf(id);
    }

    @PutMapping("/{id}/tags")
    public List<Dtos.TagDto> setTags(@PathVariable String id, @RequestBody List<String> names) {
        tagRepository.setNodeTags(id, names);
        Note n = noteService.require(id);
        noteRepository.update(n);
        return tagRepository.tagsOf(id);
    }

    // ---------------- aliases ----------------

    @PutMapping("/{id}/aliases")
    public List<String> setAliases(@PathVariable String id, @RequestBody List<String> aliases) {
        noteService.require(id);
        noteService.setAliases(id, aliases);
        return noteService.aliasesOf(id);
    }

    // ---------------- daily / templates ----------------

    @PostMapping("/daily")
    public Dtos.NoteDetail daily(@RequestParam(required = false) String date,
                                 @RequestParam(required = false) String templateId) {
        return noteService.detail(noteService.dailyNote(date, templateId));
    }


    // ---------------- unresolved links ----------------

    @PostMapping("/resolve-stub")
    public Dtos.NoteDetail resolveStub(@RequestBody Map<String, String> body) {
        String id = noteService.createFromStub(body.get("title"), body.get("fromNodeId"));
        return noteService.detail(id);
    }
}
