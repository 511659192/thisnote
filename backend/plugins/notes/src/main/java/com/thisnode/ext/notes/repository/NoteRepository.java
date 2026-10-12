package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists note metadata only. Card files and card-content operations belong to CardRepository.
 */
@Repository
@RequiredArgsConstructor
public class NoteRepository {

    private final NotesConfig notesConfig;
    private final IdFileIndex fileIndex;

    @SneakyThrows
    public String saveNew(NoteMeta note) {
        String noteId = IdUtil.randomUUID();
        String fileName = "tmp-" + noteId + ".json";
        JsonFileUtils.write(notesConfig.getNoteMetaDir().resolve(fileName), note.setNoteId(noteId));
        fileIndex.addNote(noteId, fileName);
        return noteId;
    }

    /**
     * Rename only the physical metadata file; the noteMeta ID remains stable.
     */
    @SneakyThrows
    public void onNewTitle(NoteMeta noteMeta,
                           String newTitle) {
        String noteId = noteMeta.getNoteId();
        String oldFileName = fileIndex.noteFileName(noteId);
        if (oldFileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + noteId);

        noteMeta.setTitle(newTitle).setUpdatedAt(System.currentTimeMillis());
        String newFileName = newTitle + ".json";
        JsonFileUtils.move(notesConfig.getNoteMetaDir().resolve(oldFileName), notesConfig.getNoteMetaDir().resolve(newFileName));

        noteMeta.setUpdatedAt(System.currentTimeMillis());
        JsonFileUtils.write(notesConfig.getNoteMetaDir().resolve(newFileName), noteMeta);
        fileIndex.updateNoteFileName(noteId, newFileName);
    }

    public void save(NoteMeta noteMeta) {
        if (noteMeta == null || StrUtil.isBlank(noteMeta.getNoteId())) return;
        rewrite(noteMeta);
    }

    /**
     * Rewrite the persisted shape without changing the noteMeta's updatedAt.
     */
    @SneakyThrows
    public void rewrite(NoteMeta noteMeta) {
        if (noteMeta == null || StrUtil.isBlank(noteMeta.getNoteId())) return;
        String fileName = fileIndex.noteFileName(noteMeta.getNoteId());
        if (fileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + noteMeta.getNoteId());

        noteMeta.setUpdatedAt(System.currentTimeMillis());
        Files.setLastModifiedTime(notesConfig.getNoteMetaDir().resolve(fileName), FileTime.from(Instant.now()));
        JsonFileUtils.write(notesConfig.getNoteMetaDir().resolve(fileName), noteMeta);
    }

    /**
     * Delete only the note meta; CardService removes its card files first.
     */
    public void delete(String noteId) {
        String fileName = fileIndex.noteFileName(noteId);
        if (fileName == null) return;
        fileIndex.removeNote(noteId);
        FileUtil.del(notesConfig.getNoteMetaDir().resolve(fileName));
    }

    public List<NoteMeta> getAll() {
        List<NoteMeta> notes = new ArrayList<>();
        Path metaDir = notesConfig.getNoteMetaDir();
        for (String fileName : fileIndex.noteFiles().values()) {
            NoteMeta note = NoteMeta.parse(metaDir.resolve(fileName));
            if (note != null) notes.add(note);
        }
        return notes;
    }

    /**
     * Read one note's metadata through the persistent ID-to-filename index.
     */
    public NoteMeta getOne(String noteId) {
        if (StrUtil.isBlank(noteId)) return null;
        String fileName = fileIndex.noteFileName(noteId);
        return fileName == null ? null : NoteMeta.parse(notesConfig.getNoteMetaDir().resolve(fileName));
    }

}
