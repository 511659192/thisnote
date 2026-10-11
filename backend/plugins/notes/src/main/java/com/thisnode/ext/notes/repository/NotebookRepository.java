package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Notebook;
import com.thisnode.ext.notes.util.JsonFileUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Persists the flat notebook list; note membership belongs to NotebookService. */
@Repository
@RequiredArgsConstructor
public class NotebookRepository {

    private static final Logger log = LoggerFactory.getLogger(NotebookRepository.class);

    private final NotesConfig notesConfig;

    @PostConstruct
    private void initializeDefaultGroup() {
        List<Notebook> notebooks = getAll();
        if (getOne(notebooks, Notebook.DEFAULT_GROUP_ID) == null) {
            long now = System.currentTimeMillis();
            notebooks.add(Notebook.fresh(Notebook.DEFAULT_GROUP_ID, Notebook.TYPE_NOTEBOOK,
                    Notebook.DEFAULT_GROUP_TITLE, "📥", 0, now));
            save(notebooks);
        }
    }

    private Path ledgerPath() {
        return notesConfig.getNotebookDir().resolve("notebooks.json");
    }

    private List<Notebook> getAll() {
        Path file = ledgerPath();
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<Notebook> loaded = JSON.parseArray(json, Notebook.class);
            return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
        } catch (IOException | RuntimeException e) {
            log.warn("分组清单无法读取，按空清单处理：{}", e.toString());
            return new ArrayList<>();
        }
    }

    private void save(List<Notebook> notebooks) {
        JsonFileUtils.write(ledgerPath(), notebooks);
    }

    public List<Notebook> getAll(String type) {
        String wantedType = type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type;
        return getAll().stream()
                .filter(notebook -> notebook.type().equals(wantedType))
                .sorted(Comparator.comparingInt(Notebook::sortOrder)
                        .thenComparing(Notebook::title, String.CASE_INSENSITIVE_ORDER))
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
            max = Math.max(max, notebook.sortOrder());
        }
        notebooks.add(Notebook.fresh(id,
                type == null || type.isBlank() ? Notebook.TYPE_NOTEBOOK : type,
                title, icon, max + 1, now));
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
        Notebook updated = new Notebook(current.v(), id, current.type(),
                title == null || title.isBlank() ? current.title() : title.trim(),
                icon == null ? current.icon() : icon,
                sortOrder == null ? current.sortOrder() : sortOrder,
                current.createdAt(), System.currentTimeMillis());
        for (int i = 0; i < notebooks.size(); i++) {
            if (id.equals(notebooks.get(i).id())) {
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
        notebooks.removeIf(notebook -> id.equals(notebook.id()));
        save(notebooks);
    }

    private static Notebook getOne(List<Notebook> notebooks,
                                   String id) {
        if (id == null) {
            return null;
        }
        for (Notebook notebook : notebooks) {
            if (id.equals(notebook.id())) {
                return notebook;
            }
        }
        return null;
    }
}
