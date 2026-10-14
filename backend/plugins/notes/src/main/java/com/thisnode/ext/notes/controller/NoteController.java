package com.thisnode.ext.notes.controller;

import com.thisnode.ext.notes.model.NoteMeta;
import com.thisnode.ext.notes.model.request.CreateNoteRequest;
import com.thisnode.ext.notes.model.request.NoteListQuery;
import com.thisnode.ext.notes.model.request.UpdateNoteRequest;
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

    @GetMapping("/get")
    public List<NoteMeta> list(@RequestParam(required = false) String notebookId,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String text,
                               @RequestParam(defaultValue = "500") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteListQuery(notebookId, type, text, limit, offset));
    }

    @PostMapping("/create")
    public NoteMeta create(@RequestBody CreateNoteRequest req) {
        return noteService.get(noteService.create(req));
    }

    @GetMapping("/get-detail")
    public NoteMeta detail(@RequestParam String noteId) {
        return noteService.get(noteId);
    }

    @PostMapping("/update")
    public NoteMeta update(@RequestParam String noteId,
                           @RequestBody UpdateNoteRequest req) {
        return noteService.update(noteId, req);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String id) {
        noteService.delete(id);
        return Map.of("ok", true);
    }
}
