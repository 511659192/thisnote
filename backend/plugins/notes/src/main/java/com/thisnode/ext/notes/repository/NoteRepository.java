package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
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
        String noteId = StrUtil.isBlank(note.getNoteId()) ? java.util.UUID.randomUUID().toString() : note.getNoteId();
        note.setNoteId(noteId);

        String fileName = "draft-" + note.getNoteId() + ".json";
        Path file = notesConfig.getNoteMetaDir().resolve(fileName);
        JsonFileUtils.write(file, note);
        fileIndex.addNote(noteId, fileName);
        return noteId;
    }

    /**
     * Rename only the physical metadata file; the note ID remains stable.
     */
    @SneakyThrows
    public void saveRenamed(NoteMeta note,
                            String newTitle) {
        String noteId = note.getNoteId();
        String oldFileName = fileIndex.noteFileName(noteId);
        if (oldFileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + noteId);
        note.setTitle(newTitle).setUpdatedAt(System.currentTimeMillis());
        Path metaDir = notesConfig.getNoteMetaDir();
        String newFileName = newTitle + ".json";
        if (newFileName.equals(oldFileName)) {
            JsonFileUtils.write(metaDir.resolve(oldFileName), note);
            return;
        }
        JsonFileUtils.move(metaDir.resolve(oldFileName), metaDir.resolve(newFileName));
        JsonFileUtils.write(metaDir.resolve(newFileName), note);

        fileIndex.updateNoteFileName(noteId, newFileName);
    }


    public void save(NoteMeta note) {
        if (note == null || StrUtil.isBlank(note.getNoteId())) return;
        note.setUpdatedAt(System.currentTimeMillis());
        rewrite(note);
    }

    /**
     * Rewrite the persisted shape without changing the note's updatedAt.
     */
    public void rewrite(NoteMeta note) {
        if (note == null || StrUtil.isBlank(note.getNoteId())) return;
        String fileName = fileIndex.noteFileName(note.getNoteId());
        if (fileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + note.getNoteId());
        JsonFileUtils.write(notesConfig.getNoteMetaDir().resolve(fileName), note);
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
