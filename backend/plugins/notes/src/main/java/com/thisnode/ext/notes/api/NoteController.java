package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.TagRepository;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.domain.NoteMeta;
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
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String notebookId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String text,

            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteService.Query(tag, notebookId, type, text, limit, offset));
    }

    @PostMapping("/create")
    public Dtos.NoteDetail create(@RequestBody Dtos.CreateNodeRequest req) {
        return noteService.detail(noteService.create(req));
    }

    @GetMapping("/detail")
    public Dtos.NoteDetail detail(@RequestParam String id) {
        return noteService.detail(id);
    }

    @PostMapping("/update")
    public Dtos.NoteDetail update(@RequestParam String id, @RequestBody Dtos.UpdateNodeRequest req) {
        if (req != null && req.contentMd() != null) {
            throw new IllegalArgumentException(
                    "整篇正文改写已停用：请在卡片里编辑（POST /api/notes/{noteId}/cards/{cardId}/content）");
        }
        noteService.update(id, req);
        return noteService.detail(id);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String id) {
        noteService.delete(id);
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
        String id = noteService.createFromStub(body.get("title"));
        return noteService.detail(id);
    }
}
