package com.thisnote.ext.notes.repository;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.BookPO;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/**
 * Persists the flat group list; group lifecycle rules belong to BookService.
 */
@Repository
@RequiredArgsConstructor
public class BookRepository {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public List<BookPO> getAll() {
        Path file = notesConfig.getRootDir().resolve("book").resolve(".books.json");
        return BookPO.parse(file);
    }

    public void updateAll(List<BookPO> books) {
        Path file = notesConfig.getRootDir().resolve("book").resolve(".books.json");
        fileHelper.fileWritten(file, books);
    }

    public synchronized void reset() {
        Path file = notesConfig.getRootDir().resolve("book").resolve(".books.json");
        fileHelper.fileWritten(file, List.of(BookPO.defaultGroup(System.currentTimeMillis())));
    }

    public BookPO getOne(String id) {
        return getOne(getAll(), id);
    }

    private static BookPO getOne(List<BookPO> books,
                                   String id) {
        if (id == null) {
            return null;
        }
        return books.stream()
                .filter(book -> id.equals(book.getId()))
                .findFirst()
                .orElse(null);
    }
}
