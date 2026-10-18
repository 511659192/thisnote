package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.model.NotesMapper;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.ext.notes.model.BookPO;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.BookRepository;
import com.thisnode.plugin.api.event.BookChangedEvent;
import com.thisnode.plugin.api.notes.dto.BookDTO;
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
public class BookService {

    private final BookRepository bookRepository;
    private final NoteRepository noteRepository;
    private final NotesMapper notesMapper;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    public List<BookDTO> list(String type) {
        return bookRepository.getAll(type).stream().map(notesMapper::toBookDTO).toList();
    }

    public BookDTO create(String title,
                         String icon,
                         String type) {
        String id = bookRepository.create(title, icon, type);
        String savedTitle = title == null ? "" : title;
        String savedType = type == null || type.isBlank() ? BookPO.TYPE_BOOK : type;
        mainEventPublisher.publishEvent(new BookChangedEvent(
                id, BookChangedEvent.Kind.CREATED, savedTitle, savedType, List.of()));
        log.info("创建分组「{}」（type={}，id={}）", title, type, id);
        return notesMapper.toBookDTO(bookRepository.getOne(id));
    }

    public BookDTO update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        BookPO updated = bookRepository.update(id, title, icon, sortOrder);
        mainEventPublisher.publishEvent(new BookChangedEvent(
                id, BookChangedEvent.Kind.UPDATED, updated.getTitle(), updated.getType(), List.of()));
        log.info("更新分组 {}（title={}，icon={}，sortOrder={}）", id, title, icon, sortOrder);
        return notesMapper.toBookDTO(updated);
    }

    public void delete(String id) {
        if (BookPO.DEFAULT_GROUP_ID.equals(id)) {
            throw new IllegalArgumentException("「" + BookPO.DEFAULT_GROUP_TITLE + "」是默认分组，不能删除：笔记必须归属一个分组，它是无归属笔记的落脚点");
        }
        BookPO deleted = bookRepository.getOne(id);
        List<String> movedNoteIds = new ArrayList<>();
        for (NotePO note : noteRepository.getAll()) {
            if (id.equals(note.getBookId())) {
                note.setBookId(BookPO.DEFAULT_GROUP_ID);
                noteRepository.update(note);
                movedNoteIds.add(note.getNoteId());
            }
        }
        bookRepository.delete(id);
        if (deleted != null) {
            mainEventPublisher.publishEvent(new BookChangedEvent(
                    id,
                    BookChangedEvent.Kind.DELETED,
                    deleted.getTitle(),
                    deleted.getType(),
                    movedNoteIds));
        }
        log.info("删除分组 {}，{} 篇笔记归入默认分组", id, movedNoteIds.size());
    }

}
