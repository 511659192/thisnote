package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.CreateNoteRequest;
import com.thisnode.ext.notes.api.dto.NoteDetailDto;
import com.thisnode.ext.notes.api.dto.NoteListQuery;
import com.thisnode.ext.notes.api.dto.UpdateNoteRequest;
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
        return noteService.list(new NoteListQuery(notebookId, type, text, limit, offset));
    }

    @PostMapping("/create")
    public NoteDetailDto create(@RequestBody CreateNoteRequest req) {
        return noteService.detail(noteService.create(req));
    }

    @GetMapping("/detail")
    public NoteDetailDto detail(@RequestParam String id) {
        return noteService.detail(id);
    }

    @PostMapping("/update")
    public NoteDetailDto update(@RequestParam String id,
                                @RequestBody UpdateNoteRequest req) {
        noteService.update(id, req);
        return noteService.detail(id);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String id) {
        noteService.delete(id);
        return Map.of("ok", true);
    }
}
