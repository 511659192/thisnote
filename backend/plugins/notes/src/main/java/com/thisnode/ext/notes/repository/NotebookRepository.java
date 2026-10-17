package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.NotebookPO;
import com.thisnode.plugin.api.util.FileHelper;
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
public class NotebookRepository {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public void initializeDefaultGroup() {
        List<NotebookPO> notebooks = getAll();
        if (getOne(notebooks, NotebookPO.DEFAULT_GROUP_ID) == null) {
            notebooks.add(NotebookPO.newNotebook(NotebookPO.DEFAULT_GROUP_ID, NotebookPO.TYPE_NOTEBOOK, NotebookPO.DEFAULT_GROUP_TITLE, "📥", 0, System.currentTimeMillis()));
            save(notebooks);
        }
    }

    private List<NotebookPO> getAll() {
        Path file = notesConfig.getNotebookDir().resolve("notebooks.json");
        return NotebookPO.parse(file);
    }

    private void save(List<NotebookPO> notebooks) {
        Path file = notesConfig.getNotebookDir().resolve("notebooks.json");
        fileHelper.fileWritten(file, notebooks);
    }

    public List<NotebookPO> getAll(String type) {
        String wantedType = type == null || type.isBlank() ? NotebookPO.TYPE_NOTEBOOK : type;
        return getAll().stream()
                       .filter(notebook -> notebook.getType().equals(wantedType))
                       .sorted(Comparator.comparingInt(NotebookPO::getSortOrder).thenComparing(NotebookPO::getTitle, String.CASE_INSENSITIVE_ORDER))
                       .toList();
    }

    public NotebookPO getOne(String id) {
        return getOne(getAll(), id);
    }

    public String create(String title,
                         String icon,
                         String type) {
        List<NotebookPO> notebooks = getAll();
        String id = IdUtil.randomUUID();
        long now = System.currentTimeMillis();
        int max = 0;
        for (NotebookPO notebook : notebooks) {
            max = Math.max(max, notebook.getSortOrder());
        }
        notebooks.add(NotebookPO.newNotebook(id, type == null || type.isBlank() ? NotebookPO.TYPE_NOTEBOOK : type, title, icon, max + 1, now));
        save(notebooks);
        return id;
    }

    public NotebookPO update(String id,
                           String title,
                           String icon,
                           Integer sortOrder) {
        List<NotebookPO> notebooks = getAll();
        NotebookPO current = getOne(notebooks, id);
        if (current == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        NotebookPO updated = new NotebookPO(current.getV(),
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
        List<NotebookPO> notebooks = getAll();
        notebooks.removeIf(notebook -> id.equals(notebook.getId()));
        save(notebooks);
    }

    private static NotebookPO getOne(List<NotebookPO> notebooks,
                                   String id) {
        if (id == null) {
            return null;
        }
        for (NotebookPO notebook : notebooks) {
            if (id.equals(notebook.getId())) {
                return notebook;
            }
        }
        return null;
    }
}
