package com.thisnode.ext.notes.controller;

import com.thisnode.ext.notes.model.request.CreateNoteRequest;
import com.thisnode.ext.notes.model.request.DeleteNotesRequest;
import com.thisnode.ext.notes.model.request.MoveNotesRequest;
import com.thisnode.ext.notes.model.request.NoteListRequest;
import com.thisnode.ext.notes.model.request.UpdateNoteRequest;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.plugin.api.notes.dto.NoteDTO;
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
    public List<NoteDTO> list(@RequestParam(required = false) String bookId,
                              @RequestParam(required = false) String text,
                              @RequestParam(defaultValue = "500") int limit,
                              @RequestParam(defaultValue = "0") int offset) {
        return noteService.list(new NoteListRequest(bookId, text, limit, offset));
    }

    @PostMapping("/create")
    public NoteDTO create(@RequestBody CreateNoteRequest req) {
        return noteService.create("", req.bookId(), List.of());
    }

    @GetMapping("/get-detail")
    public NoteDTO detail(@RequestParam String noteId) {
        return noteService.get(noteId);
    }

    @PostMapping("/update")
    public NoteDTO update(@RequestBody UpdateNoteRequest req) {
        return noteService.update(req.noteId(), req);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String id) {
        noteService.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/delete-batch")
    public Map<String, Object> deleteBatch(@RequestBody DeleteNotesRequest req) {
        noteService.deleteAll(req.noteIds());
        return Map.of("ok", true);
    }

    @PostMapping("/move")
    public Map<String, Object> move(@RequestBody MoveNotesRequest req) {
        noteService.moveAll(req.noteIds(), req.bookId());
        return Map.of("ok", true);
    }
}
