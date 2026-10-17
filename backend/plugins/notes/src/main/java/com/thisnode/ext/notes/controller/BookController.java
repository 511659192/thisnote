package com.thisnode.ext.notes.controller;

import com.thisnode.ext.notes.model.request.CreateBookRequest;
import com.thisnode.ext.notes.model.request.UpdateBookRequest;
import com.thisnode.plugin.api.notes.dto.BookDTO;
import com.thisnode.ext.notes.service.BookService;
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
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    @GetMapping("/get")
    public List<BookDTO> list() {
        return bookService.list("book");
    }

    @PostMapping("/create")
    public BookDTO create(@RequestBody CreateBookRequest req) {
        return bookService.create(req.title(), req.icon(), "book");
    }

    @PostMapping("/update")
    public BookDTO update(@RequestParam String id, @RequestBody UpdateBookRequest req) {
        return bookService.update(id, req.title(), req.icon(), req.sortOrder());
    }

    @PostMapping("/delete")
    public java.util.Map<String, Object> delete(@RequestParam String id) {
        bookService.delete(id);
        return java.util.Map.of("ok", true);
    }
}
