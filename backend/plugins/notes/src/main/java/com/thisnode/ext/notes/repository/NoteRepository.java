package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persists note metadata only. Card files and card-content operations belong to CardRepository. */
@Repository
public class NoteRepository {

    private final NotesConfig nacosConfig;

    @Autowired
    public NoteRepository(NotesConfig config) {
        this(config.markdownRoot());
    }

    public NoteRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public String insert(String title, String type) {
        String id = IdUtil.randomUUID();
        save(new NoteMeta().setId(id).setTitle(title).setType(type));
        return id;
    }

    public void save(NoteMeta note) {
        if (note == null || StrUtil.isBlank(note.getId())) {
            return;
        }
        JsonFileUtils.write(metaPath(note.getId()), note);
    }

    /** Delete only the note meta; CardService removes its card files first. */
    public void delete(String id) {
        FileUtil.del(metaPath(id));
    }

    @SneakyThrows
    public List<NoteMeta> getAll() {
        Path metaDir = root.resolve("meta");
        if (!Files.isDirectory(metaDir)) {
            return List.of();
        }
        List<NoteMeta> notes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metaDir, "*.json")) {
            for (Path file : stream) {
                NoteMeta note = NoteMeta.parse(file);
                if (note != null) {
                    notes.add(note);
                }
            }
        }
        return notes;
    }

    /** Read one note's metadata without scanning the library. */
    public NoteMeta getOne(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        return NoteMeta.parse(metaPath(id));
    }

    private Path metaPath(String id) {
        return root.resolve("meta").resolve(id + ".json");
    }
}
