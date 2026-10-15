package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.model.NoteMeta;
import com.thisnode.ext.notes.model.Notebook;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.NotebookRepository;
import com.thisnode.plugin.api.NotebookChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates notebook-ledger operations with note metadata membership.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotebookService {

    private final NotebookRepository notebookRepository;
    private final NoteRepository noteRepository;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    public List<Notebook> list(String type) {
        return notebookRepository.getAll(type);
    }

    public Notebook find(String id) {
        return notebookRepository.getOne(id);
    }

    public String create(String title,
                         String icon,
                         String type) {
        String id = notebookRepository.create(title, icon, type);
        String savedTitle = title == null ? "" : title;
        String savedType = type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type;
        requireMainEventPublisher().publishEvent(new NotebookChangedEvent(
                id, NotebookChangedEvent.Kind.CREATED, savedTitle, savedType, List.of()));
        log.info("创建分组「{}」（type={}，id={}）", title, type, id);
        return id;
    }

    public Notebook update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        Notebook updated = notebookRepository.update(id, title, icon, sortOrder);
        requireMainEventPublisher().publishEvent(new NotebookChangedEvent(
                id, NotebookChangedEvent.Kind.UPDATED, updated.getTitle(), updated.getType(), List.of()));
        log.info("更新分组 {}（title={}，icon={}，sortOrder={}）", id, title, icon, sortOrder);
        return updated;
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        if (Notebook.DEFAULT_GROUP_ID.equals(id)) {
            throw new IllegalArgumentException("「" + Notebook.DEFAULT_GROUP_TITLE + "」是默认分组，不能删除：笔记必须归属一个分组，它是无归属笔记的落脚点");
        }
        Notebook deleted = notebookRepository.getOne(id);
        List<String> movedNoteIds = new ArrayList<>();
        for (NoteMeta note : noteRepository.getAll()) {
            if (id.equals(note.getNotebookId())) {
                note.setNotebookId(Notebook.DEFAULT_GROUP_ID);
                noteRepository.save(note);
                movedNoteIds.add(note.getNoteId());
            }
        }
        notebookRepository.delete(id);
        if (deleted != null) {
            requireMainEventPublisher().publishEvent(new NotebookChangedEvent(
                    id,
                    NotebookChangedEvent.Kind.DELETED,
                    deleted.getTitle(),
                    deleted.getType(),
                    movedNoteIds));
        }
        log.info("删除分组 {}，{} 篇笔记归入默认分组", id, movedNoteIds.size());
    }

    private ApplicationEventPublisher requireMainEventPublisher() {
        ApplicationEventPublisher publisher = mainEventPublisher;
        if (publisher == null) {
            throw new IllegalStateException("主容器事件发布器尚未就绪");
        }
        return publisher;
    }


}
