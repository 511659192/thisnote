package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Notebook;
import com.thisnode.ext.notes.util.JsonFileUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persists the flat notebook list; note membership belongs to NotebookService.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class NotebookRepository {

    private final NotesConfig notesConfig;

    @PostConstruct
    private void initializeDefaultGroup() {
        List<Notebook> notebooks = getAll();
        if (getOne(notebooks, Notebook.DEFAULT_GROUP_ID) == null) {
            notebooks.add(Notebook.newNotebook(Notebook.DEFAULT_GROUP_ID, Notebook.TYPE_NOTEBOOK, Notebook.DEFAULT_GROUP_TITLE, "📥", 0, System.currentTimeMillis()));
            save(notebooks);
        }
    }

    private List<Notebook> getAll() {
        Path file = notesConfig.getNotebookDir().resolve("notebooks.json");
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }

        return Notebook.parse(file);
    }

    private void save(List<Notebook> notebooks) {
        JsonFileUtils.write(notesConfig.getNotebookDir().resolve("notebooks.json"), notebooks);
    }

    public List<Notebook> getAll(String type) {
        String wantedType = type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type;
        return getAll().stream()
                       .filter(notebook -> notebook.getType().equals(wantedType))
                       .sorted(Comparator.comparingInt(Notebook::getSortOrder).thenComparing(Notebook::getTitle, String.CASE_INSENSITIVE_ORDER))
                       .toList();
    }

    public Notebook getOne(String id) {
        return getOne(getAll(), id);
    }

    public String create(String title,
                         String icon,
                         String type) {
        List<Notebook> notebooks = getAll();
        String id = IdUtil.randomUUID();
        long now = System.currentTimeMillis();
        int max = 0;
        for (Notebook notebook : notebooks) {
            max = Math.max(max, notebook.getSortOrder());
        }
        notebooks.add(Notebook.newNotebook(id, type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type, title, icon, max + 1, now));
        save(notebooks);
        return id;
    }

    public Notebook update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        List<Notebook> notebooks = getAll();
        Notebook current = getOne(notebooks, id);
        if (current == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        Notebook updated = new Notebook(current.getV(),
                                        id,
                                        current.getType(),
                                        title == null || title.isBlank() ? current.getTitle() : title.trim(),
                                        icon == null ? current.getIcon() : icon,
                                        sortOrder == null ? current.getSortOrder() : sortOrder,
                                        current.getCreatedAt(),
                                        System.currentTimeMillis());
        for (int i = 0; i < notebooks.size(); i++) {
            if (id.equals(notebooks.get(i).getId())) {
                notebooks.set(i, updated);
                break;
            }
        }
        save(notebooks);
        return updated;
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        List<Notebook> notebooks = getAll();
        notebooks.removeIf(notebook -> id.equals(notebook.getId()));
        save(notebooks);
    }

    private static Notebook getOne(List<Notebook> notebooks,
                                   String id) {
        if (id == null) {
            return null;
        }
        for (Notebook notebook : notebooks) {
            if (id.equals(notebook.getId())) {
                return notebook;
            }
        }
        return null;
    }
}
