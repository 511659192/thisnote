package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.NoteMetaPO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists note metadata only. Card files and card-content operations belong to CardRepository.
 */
@Repository
@RequiredArgsConstructor
public class NoteRepository {

    private final NotesConfig notesConfig;
    private final IdFileIndex fileIndex;
    private final FileHelper fileHelper;

    public String saveNew(NoteMetaPO note) {
        String noteId = IdUtil.randomUUID();
        String baseName = StrUtil.isBlank(note.getTitle()) ? "tmp-" + noteId : note.getTitle();
        String fileName = baseName + ".json";
        Path metaDir = notesConfig.getNoteMetaDir();
        Path file = metaDir.resolve(fileName);
        fileHelper.fileWritten(file, note.setNoteId(noteId));
        fileIndex.addNote(noteId, fileName);
        return noteId;
    }

    /**
     * Rename only the physical metadata file; the noteMeta ID remains stable.
     */
    @SneakyThrows
    public void newTitle(NoteMetaPO noteMeta,
                         String newTitle) {
        String noteId = noteMeta.getNoteId();
        String oldFileName = fileIndex.noteFileName(noteId);
        if (oldFileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + noteId);

        noteMeta.setTitle(newTitle);
        String newFileName = newTitle + ".json";
        Path oldFile = notesConfig.getNoteMetaDir().resolve(oldFileName);
        Path newFile = notesConfig.getNoteMetaDir().resolve(newFileName);
        fileHelper.fileMoved(oldFile, newFile);
        fileHelper.fileWritten(newFile, noteMeta);
        fileIndex.updateNoteFileName(noteId, newFileName);
    }

    public void save(NoteMetaPO noteMeta) {
        if (noteMeta == null || StrUtil.isBlank(noteMeta.getNoteId())) return;
        rewrite(noteMeta);
    }

    /** Rewrite note metadata and refresh its filesystem modification time. */
    public void rewrite(NoteMetaPO noteMeta) {
        if (noteMeta == null || StrUtil.isBlank(noteMeta.getNoteId())) return;
        String fileName = fileIndex.noteFileName(noteMeta.getNoteId());
        if (fileName == null) throw new IllegalStateException("笔记 ID 不在文件索引中：" + noteMeta.getNoteId());

        Path file = notesConfig.getNoteMetaDir().resolve(fileName);
        fileHelper.fileWritten(file, noteMeta);
    }

    /**
     * Delete only the note meta; CardService removes its card files first.
     */
    public void delete(String noteId) {
        String fileName = fileIndex.noteFileName(noteId);
        if (fileName == null) return;
        fileIndex.removeNote(noteId);
        Path file = notesConfig.getNoteMetaDir().resolve(fileName);
        fileHelper.fileDeleted(file);
    }

    @SneakyThrows
    public List<NoteMetaPO> getAll() {
        Path metaDir = notesConfig.getNoteMetaDir();
        List<Path> files = new ArrayList<>();
        Map<Path, FileTime> modifiedTimes = new HashMap<>();
        for (String fileName : fileIndex.noteFiles().values()) {
            Path file = metaDir.resolve(fileName);
            FileTime modifiedTime;
            try {
                modifiedTime = Files.getLastModifiedTime(file);
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.add(file);
            modifiedTimes.put(file, modifiedTime);
        }
        files.sort(Comparator.comparing(modifiedTimes::get).reversed());

        List<NoteMetaPO> notes = new ArrayList<>();
        for (Path file : files) {
            NoteMetaPO note = NoteMetaPO.parse(file);
            if (note != null) notes.add(note);
        }
        return notes;
    }

    /**
     * Read one note's metadata through the persistent ID-to-filename index.
     */
    public NoteMetaPO getOne(String noteId) {
        if (StrUtil.isBlank(noteId)) return null;
        String fileName = fileIndex.noteFileName(noteId);
        return fileName == null ? null : NoteMetaPO.parse(notesConfig.getNoteMetaDir().resolve(fileName));
    }

}
