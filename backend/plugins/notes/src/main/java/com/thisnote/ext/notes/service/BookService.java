package com.thisnote.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.model.NotePO;
import com.thisnote.ext.notes.model.BookPO;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.ext.notes.repository.BookRepository;
import com.thisnote.plugin.api.event.BookChangedEvent;
import com.thisnote.plugin.api.notes.dto.BookDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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
        String wantedType = type == null || type.isBlank() ? BookPO.TYPE_BOOK : type;
        return bookRepository.getAll().stream()
                .filter(book -> book.getType().equals(wantedType))
                .sorted(Comparator.comparingInt(BookPO::getSortOrder)
                        .thenComparing(BookPO::getTitle, String.CASE_INSENSITIVE_ORDER))
                .map(notesMapper::toBookDTO)
                .toList();
    }

    public void initializeDefaultGroup() {
        List<BookPO> books = bookRepository.getAll();
        if (books.stream().noneMatch(book -> BookPO.DEFAULT_GROUP_ID.equals(book.getId()))) {
            books.add(BookPO.newBook(
                    BookPO.DEFAULT_GROUP_ID,
                    BookPO.TYPE_BOOK,
                    BookPO.DEFAULT_GROUP_TITLE,
                    "📥",
                    0,
                    System.currentTimeMillis()));
            bookRepository.save(books);
        }
    }

    public BookDTO create(String title,
                         String icon,
                         String type) {
        List<BookPO> books = bookRepository.getAll();
        String id = IdUtil.randomUUID();
        int sortOrder = books.stream()
                .mapToInt(BookPO::getSortOrder)
                .max()
                .orElse(-1) + 1;
        String savedType = type == null || type.isBlank() ? BookPO.TYPE_BOOK : type;
        BookPO created = BookPO.newBook(id, savedType, title, icon, sortOrder, System.currentTimeMillis());
        books.add(created);
        bookRepository.save(books);
        String savedTitle = title == null ? "" : title;
        mainEventPublisher.publishEvent(new BookChangedEvent(
                id, BookChangedEvent.Kind.CREATED, savedTitle, savedType, List.of()));
        log.info("创建分组「{}」（type={}，id={}）", title, type, id);
        return notesMapper.toBookDTO(created);
    }

    public BookDTO update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        List<BookPO> books = bookRepository.getAll();
        BookPO current = books.stream()
                .filter(book -> book.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分组不存在：" + id));
        BookPO updated = new BookPO(
                id,
                current.getType(),
                title == null || title.isBlank() ? current.getTitle() : title.trim(),
                icon == null ? current.getIcon() : icon,
                sortOrder == null ? current.getSortOrder() : sortOrder,
                current.getCreatedAt(),
                System.currentTimeMillis());
        for (int index = 0; index < books.size(); index++) {
            if (id.equals(books.get(index).getId())) {
                books.set(index, updated);
                break;
            }
        }
        bookRepository.save(books);
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
        List<NotePO> moved = new ArrayList<>();
        for (NotePO note : noteRepository.getAll()) {
            if (id.equals(note.getBookId())) {
                note.setBookId(BookPO.DEFAULT_GROUP_ID);
                moved.add(note);
            }
        }
        noteRepository.updateAll(moved);
        List<BookPO> books = bookRepository.getAll();
        books.removeIf(book -> id.equals(book.getId()));
        bookRepository.save(books);
        List<String> movedNoteIds = moved.stream().map(NotePO::getNoteId).toList();
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
