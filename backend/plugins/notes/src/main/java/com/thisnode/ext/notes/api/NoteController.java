package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.domain.TagRepository;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.store.NoteMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteService.Query(filter, tag, notebookId, type, text,
                false, limit, offset));
    }

    @PostMapping
    public Dtos.NoteDetail create(@RequestBody Dtos.CreateNodeRequest req) {
        return noteService.detail(noteService.create(req));
    }

    @GetMapping("/detail")
    public Dtos.NoteDetail detail(@RequestParam String id) {
        return noteService.detail(id);
    }

    /**
     * 改笔记级字段（标题 / 标签 / 来源…）。
     *
     * <p><b>正文不在这里改</b>：带 {@code contentMd} 的整篇改写会把全文写进第一张卡、同时清空
     * 其余卡片的正文（{@code NoteRepository#replaceNoteContent}），是一次静默的数据丢失。正文一律走
     * 卡片端点 {@code POST /api/notes/{noteId}/cards/{cardId}/content}（那边带冲突保护）。
     */
    @PostMapping("/patch")
    public Dtos.NoteDetail patch(@RequestParam String id, @RequestBody Dtos.UpdateNodeRequest req) {
        if (req != null && req.contentMd() != null) {
            throw new IllegalArgumentException(
                    "整篇正文改写已停用：请在卡片里编辑（POST /api/notes/{noteId}/cards/{cardId}/content）");
        }
        noteService.patch(id, req);
        return noteService.detail(id);
    }

    @PostMapping("/delete")
    public Map<String, Object> purge(@RequestParam String id) {
        noteService.purge(id);
        return Map.of("ok", true);
    }

    // ---------------- tags ----------------

    @PostMapping("/tags/add")
    public List<Dtos.TagDto> addTag(@RequestParam String id, @RequestParam String name) {
        noteService.require(id);
        tagRepository.addNodeTag(id, name);
        return tagRepository.tagsOf(id);
    }

    @PostMapping("/tags/remove")
    public List<Dtos.TagDto> removeTag(@RequestParam String id, @RequestParam String name) {
        noteService.require(id);
        tagRepository.removeNodeTag(id, name);
        return tagRepository.tagsOf(id);
    }


    // ---------------- unresolved links ----------------

    @PostMapping("/resolve-stub")
    public Dtos.NoteDetail resolveStub(@RequestBody Map<String, String> body) {
        String id = noteService.createFromStub(body.get("title"), body.get("fromNodeId"));
        return noteService.detail(id);
    }
}
