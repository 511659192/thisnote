package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.model.NoteMeta;
import com.thisnode.ext.notes.model.Notebook;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.NotebookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Coordinates notebook-ledger operations with note metadata membership.
 */
@Service
@RequiredArgsConstructor
public class NotebookService {

    private final NotebookRepository notebookRepository;
    private final NoteRepository noteRepository;

    public List<Notebook> list(String type) {
        return notebookRepository.getAll(type);
    }

    public Notebook find(String id) {
        return notebookRepository.getOne(id);
    }

    public String create(String title,
                         String icon,
                         String type) {
        return notebookRepository.create(title, icon, type);
    }

    public Notebook update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        return notebookRepository.update(id, title, icon, sortOrder);
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        if (Notebook.DEFAULT_GROUP_ID.equals(id)) {
            throw new IllegalArgumentException("「" + Notebook.DEFAULT_GROUP_TITLE + "」是默认分组，不能删除：笔记必须归属一个分组，它是无归属笔记的落脚点");
        }
        for (NoteMeta note : noteRepository.getAll()) {
            if (id.equals(note.getNotebookId())) {
                note.setNotebookId(Notebook.DEFAULT_GROUP_ID);
                noteRepository.save(note);
            }
        }
        notebookRepository.delete(id);
    }


}
