package com.thisnote.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.BookPO;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Persists the flat notebook list; note membership belongs to NotebookService.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class BookRepository {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public void initializeDefaultGroup() {
        List<BookPO> books = getAll();
        if (getOne(books, BookPO.DEFAULT_GROUP_ID) == null) {
            books.add(BookPO.newBook(BookPO.DEFAULT_GROUP_ID, BookPO.TYPE_BOOK, BookPO.DEFAULT_GROUP_TITLE, "📥", 0, System.currentTimeMillis()));
            save(books);
        }
    }

    private List<BookPO> getAll() {
        Path file = notesConfig.getRootDir().resolve("book").resolve(".books.json");
        return BookPO.parse(file);
    }

    private void save(List<BookPO> books) {
        Path file = notesConfig.getRootDir().resolve("book").resolve(".books.json");
        fileHelper.fileWritten(file, books);
    }

    public List<BookPO> getAll(String type) {
        String wantedType = type == null || type.isBlank() ? BookPO.TYPE_BOOK : type;
        return getAll().stream()
                       .filter(book -> book.getType().equals(wantedType))
                       .sorted(Comparator.comparingInt(BookPO::getSortOrder).thenComparing(BookPO::getTitle, String.CASE_INSENSITIVE_ORDER))
                       .toList();
    }

    public BookPO getOne(String id) {
        return getOne(getAll(), id);
    }

    public String create(String title,
                         String icon,
                         String type) {
        List<BookPO> books = getAll();
        String id = IdUtil.randomUUID();
        long now = System.currentTimeMillis();
        int max = 0;
        for (BookPO book : books) {
            max = Math.max(max, book.getSortOrder());
        }
        books.add(BookPO.newBook(id, type == null || type.isBlank() ? BookPO.TYPE_BOOK : type, title, icon, max + 1, now));
        save(books);
        return id;
    }

    public BookPO update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        List<BookPO> books = getAll();
        BookPO current = getOne(books, id);
        if (current == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        BookPO updated = new BookPO(id,
                                    current.getType(),
                                    title == null || title.isBlank() ? current.getTitle() : title.trim(),
                                    icon == null ? current.getIcon() : icon,
                                    sortOrder == null ? current.getSortOrder() : sortOrder,
                                    current.getCreatedAt(),
                                    System.currentTimeMillis());
        for (int i = 0; i < books.size(); i++) {
            if (id.equals(books.get(i).getId())) {
                books.set(i, updated);
                break;
            }
        }
        save(books);
        return updated;
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        List<BookPO> books = getAll();
        books.removeIf(book -> id.equals(book.getId()));
        save(books);
    }

    private static BookPO getOne(List<BookPO> books,
                                   String id) {
        if (id == null) {
            return null;
        }
        for (BookPO book : books) {
            if (id.equals(book.getId())) {
                return book;
            }
        }
        return null;
    }
}
