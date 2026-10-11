package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.CreateNodeRequest;
import com.thisnode.ext.notes.api.dto.NoteDetail;
import com.thisnode.ext.notes.api.dto.NoteQuery;
import com.thisnode.ext.notes.api.dto.UpdateNodeRequest;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping
    public List<NoteMeta> list(@RequestParam(required = false) String notebookId,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String text,
                               @RequestParam(defaultValue = "500") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteQuery(notebookId, type, text, limit, offset));
    }

    @PostMapping("/create")
    public NoteDetail create(@RequestBody CreateNodeRequest req) {
        return noteService.detail(noteService.create(req));
    }

    @GetMapping("/detail")
    public NoteDetail detail(@RequestParam String id) {
        return noteService.detail(id);
    }

    @PostMapping("/update")
    public NoteDetail update(@RequestParam String id,
                             @RequestBody UpdateNodeRequest req) {
        noteService.update(id, req);
        return noteService.detail(id);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String id) {
        noteService.delete(id);
        return Map.of("ok", true);
    }
}
