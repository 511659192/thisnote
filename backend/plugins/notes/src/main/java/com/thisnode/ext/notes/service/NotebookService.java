package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.domain.Notebook;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.NotebookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Coordinates notebook-ledger operations with note metadata membership. */
@Service
@RequiredArgsConstructor
public class NotebookService {

    private final NotebookRepository notebookRepository;
    private final NoteRepository noteRepository;

    public List<Dtos.NotebookDto> list(String type) {
        List<Notebook> notebooks = notebookRepository.list(type);
        List<NoteMeta> notes = noteRepository.getAll();
        List<Dtos.NotebookDto> result = new ArrayList<>(notebooks.size());
        for (Notebook notebook : notebooks) {
            int count = (int) notes.stream()
                    .filter(note -> notebook.id().equals(note.getNotebookId()))
                    .count();
            result.add(dto(notebook, count));
        }
        return result;
    }

    public Dtos.NotebookDto find(String id) {
        Notebook notebook = notebookRepository.find(id);
        return notebook == null ? null : dto(notebook, countNotes(id));
    }

    public String ensureDefaultGroup() {
        return notebookRepository.ensureDefaultGroup();
    }

    public String create(String title, String parentId, String icon, String type) {
        return notebookRepository.create(title, parentId, icon, type);
    }

    public Dtos.NotebookDto update(String id, String title, String parentId, String icon, Integer sortOrder) {
        Notebook notebook = notebookRepository.update(id, title, parentId, icon, sortOrder);
        return dto(notebook, countNotes(id));
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        if (NotebookRepository.DEFAULT_GROUP_ID.equals(id)) {
            throw new IllegalArgumentException("「" + NotebookRepository.DEFAULT_GROUP_TITLE
                    + "」是默认分组，不能删除：笔记必须归属一个分组，它是无归属笔记的落脚点");
        }
        ensureDefaultGroup();
        for (NoteMeta note : noteRepository.getAll()) {
            if (id.equals(note.getNotebookId())) {
                note.setNotebookId(NotebookRepository.DEFAULT_GROUP_ID);
                noteRepository.save(note);
            }
        }
        notebookRepository.delete(id);
    }

    public List<Dtos.NotebookDto> notebooksOf(String noteId) {
        NoteMeta note = noteRepository.getOne(noteId);
        if (note == null || note.getNotebookId() == null) {
            return List.of();
        }
        Notebook notebook = notebookRepository.find(note.getNotebookId());
        return notebook == null ? List.of() : List.of(dto(notebook, null));
    }

    private int countNotes(String notebookId) {
        int count = 0;
        for (NoteMeta note : noteRepository.getAll()) {
            if (notebookId.equals(note.getNotebookId())) {
                count++;
            }
        }
        return count;
    }

    private static Dtos.NotebookDto dto(Notebook notebook, Integer count) {
        return new Dtos.NotebookDto(notebook.id(), notebook.type(), notebook.title(),
                notebook.parentId(), notebook.icon(), notebook.sortOrder(), count,
                notebook.createdAt(), notebook.updatedAt());
    }
}
