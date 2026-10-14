package com.thisnode.ext.notes.controller;

import com.thisnode.ext.notes.model.Notebook;
import com.thisnode.ext.notes.model.request.CreateNotebookRequest;
import com.thisnode.ext.notes.model.request.UpdateNotebookRequest;
import com.thisnode.ext.notes.service.NotebookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Notebooks / folders (F-N-03). */
@RestController
@RequestMapping("/api/notebooks")
@RequiredArgsConstructor
public class NotebookController {

    private final NotebookService notebookService;

    @GetMapping("/get")
    public List<Notebook> list() {
        return notebookService.list("notebook");
    }

    @PostMapping("/create")
    public Notebook create(@RequestBody CreateNotebookRequest req) {
        String id = notebookService.create(req.title(), req.icon(), "notebook");
        return notebookService.find(id);
    }

    @PostMapping("/update")
    public Notebook update(@RequestParam String id, @RequestBody UpdateNotebookRequest req) {
        return notebookService.update(id, req.title(), req.icon(), req.sortOrder());
    }

    @PostMapping("/delete")
    public java.util.Map<String, Object> delete(@RequestParam String id) {
        notebookService.delete(id);
        return java.util.Map.of("ok", true);
    }
}
