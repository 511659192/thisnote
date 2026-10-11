package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
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

    @GetMapping
    public List<Dtos.NotebookDto> list() {
        return notebookService.list("notebook");
    }

    @PostMapping("/create")
    public Dtos.NotebookDto create(@RequestBody Dtos.NotebookCreateRequest req) {
        String id = notebookService.create(req.title(), null, req.icon(), "notebook");
        return notebookService.find(id);
    }

    @PostMapping("/update")
    public Dtos.NotebookDto update(@RequestParam String id, @RequestBody Dtos.NotebookRequest req) {
        return notebookService.update(id, req.title(), req.parentId(), req.icon(),
                req.sortOrder());
    }

    @PostMapping("/delete")
    public java.util.Map<String, Object> delete(@RequestParam String id) {
        notebookService.delete(id);
        return java.util.Map.of("ok", true);
    }
}
