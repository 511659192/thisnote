package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.NotebookRepository;
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

    private final NotebookRepository notebookRepository;

    @GetMapping
    public List<Dtos.NotebookDto> list() {
        return notebookRepository.list("notebook");
    }

    @PostMapping("/create")
    public Dtos.NotebookDto create(@RequestBody Dtos.NotebookRequest req) {
        String id = notebookRepository.create(req.title(), req.parentId(), req.icon(), "notebook");
        return notebookRepository.find(id);
    }

    @PostMapping("/update")
    public Dtos.NotebookDto update(@RequestParam String id, @RequestBody Dtos.NotebookRequest req) {
        return notebookRepository.update(id, req.title(), req.parentId(), req.icon(),
                req.sortOrder());
    }

    @PostMapping("/delete")
    public java.util.Map<String, Object> delete(@RequestParam String id) {
        if (NotebookRepository.DEFAULT_GROUP_ID.equals(id)) {
            throw new IllegalArgumentException("「" + NotebookRepository.DEFAULT_GROUP_TITLE
                    + "」是默认分组，不能删除：笔记必须归属一个分组，它是无归属笔记的落脚点");
        }
        // 删分组不删笔记：先把里面的笔记挪到默认分组，再删。
        // 否则外键级联只会删掉归属行，那些笔记会变成无归属 —— 破坏不变量。
        String fallback = notebookRepository.ensureDefaultGroup();
        for (String noteId : notebookRepository.nodeIdsOf(id)) {
            notebookRepository.addNodeToNotebook(noteId, fallback);
        }
        notebookRepository.delete(id);
        return java.util.Map.of("ok", true);
    }

    /** 把笔记移进这个分组（单归属：会离开原来的分组）。 */
    @PostMapping("/nodes")
    public java.util.Map<String, Object> addNode(@RequestParam String id,
                                                 @RequestParam String noteId) {
        notebookRepository.setExclusiveGroup(noteId, id);
        return java.util.Map.of("ok", true);
    }

}
