package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Notebook;
import com.thisnode.ext.notes.domain.NotebookBook;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persists the notebook hierarchy ledger only. Note membership belongs to NotebookService.
 */
@Repository
@RequiredArgsConstructor
public class NotebookRepository {

    private static final Logger log = LoggerFactory.getLogger(NotebookRepository.class);

    public static final String DEFAULT_GROUP_ID = NotebookBook.DEFAULT_GROUP_ID;
    public static final String DEFAULT_GROUP_TITLE = NotebookBook.DEFAULT_GROUP_TITLE;

    private final NotesConfig notesConfig;

    private Path ledgerPath() {
        return notesConfig.getNotebookDir().resolve("notebooks.json");
    }

    private NotebookBook book() {
        try {
            NotebookBook loaded = JsonFileUtils.read(ledgerPath(), NotebookBook.class);
            return loaded == null ? NotebookBook.empty() : loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("分组台账无法读取，按空台账处理：{}", e.toString());
            return NotebookBook.empty();
        }
    }

    private void save(NotebookBook next) {
        JsonFileUtils.write(ledgerPath(), next);
    }

    public List<Notebook> list(String type) {
        String wantedType = type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type;
        return book().getNotebooks().stream()
                     .filter(notebook -> notebook.type().equals(wantedType))
                     .sorted(Comparator.comparingInt(Notebook::sortOrder)
                                       .thenComparing(Notebook::title, String.CASE_INSENSITIVE_ORDER))
                     .toList();
    }

    public Notebook find(String id) {
        return book().find(id);
    }

    public String ensureDefaultGroup() {
        NotebookBook current = book();
        if (current.find(DEFAULT_GROUP_ID) != null) {
            return DEFAULT_GROUP_ID;
        }
        long now = System.currentTimeMillis();
        save(current.with(Notebook.fresh(DEFAULT_GROUP_ID, Notebook.TYPE_NOTEBOOK,
                                         DEFAULT_GROUP_TITLE, null, "📥", 0, now)));
        return DEFAULT_GROUP_ID;
    }

    public String create(String title,
                         String parentId,
                         String icon,
                         String type) {
        NotebookBook current = book();
        String parent = requireParent(parentId, current);
        String id = IdUtil.randomUUID();
        long now = System.currentTimeMillis();
        int max = 0;
        for (Notebook notebook : current.getNotebooks()) {
            max = Math.max(max, notebook.sortOrder());
        }
        save(current.with(Notebook.fresh(id,
                                         type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type,
                                         title,
                                         parent, icon, max + 1, now)));
        return id;
    }

    /**
     * Full replacement: {@code parentId=null} means move to the top level.
     */
    public Notebook update(String id,
                           String title,
                           String parentId,
                           String icon,
                           Integer sortOrder) {
        NotebookBook currentBook = book();
        Notebook current = currentBook.find(id);
        if (current == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        if (DEFAULT_GROUP_ID.equals(id) && parentId != null) {
            throw new IllegalArgumentException("默认分组不能移到别的分组下：它是无归属笔记的落脚点");
        }
        String parent = requireParent(parentId, currentBook);
        assertNoCycle(id, parent, currentBook);
        Notebook updated = new Notebook(current.v(), id, current.type(),
                                        title == null || title.isBlank() ? current.title() : title.trim(),
                                        parent,
                                        icon == null ? current.icon() : icon,
                                        sortOrder == null ? current.sortOrder() : sortOrder,
                                        current.createdAt(), System.currentTimeMillis());
        save(currentBook.with(updated));
        return updated;
    }

    private String requireParent(String parentId,
                                 NotebookBook current) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        String parent = parentId.trim();
        if (current.find(parent) == null) {
            throw new IllegalArgumentException("目标分组不存在：" + parent);
        }
        return parent;
    }

    private void assertNoCycle(String id,
                               String parent,
                               NotebookBook current) {
        String cursor = parent;
        while (cursor != null) {
            if (cursor.equals(id)) {
                throw new IllegalArgumentException("不能把分组移到它自己或它的子分组下面");
            }
            Notebook next = current.find(cursor);
            cursor = next == null ? null : next.parentId();
        }
    }

    /**
     * Remove the ledger entry and lift child groups to its former parent.
     */
    public void delete(String id) {
        if (id == null) {
            return;
        }
        NotebookBook current = book();
        Notebook gone = current.find(id);
        String fallbackParent = gone == null ? null : gone.parentId();
        NotebookBook next = current.without(id);
        List<Notebook> reparented = new ArrayList<>();
        for (Notebook notebook : next.getNotebooks()) {
            reparented.add(id.equals(notebook.parentId())
                                   ? notebook.withParent(fallbackParent) : notebook);
        }
        save(new NotebookBook(next.getV(), reparented));
    }
}
