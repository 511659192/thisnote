package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NotebookBook;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.Ids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分组（笔记本）。
 *
 * <p>台账在 {@code .notes/notebooks.json}，**归属在笔记自己的 meta 里**（{@code notebookId}）。
 * 这是与数据库表结构最重要的差别：旧模型归属是 {@code container_nodes} 这张关系表，文件模型里
 * 归属是笔记的一个字段 —— 于是"把笔记移进分组"只改一个文件，不会动到分组台账。
 *
 * <p>「笔记必须归属一个分组」这条不变量仍然只在这里实现（默认分组 {@code grp-default}）。
 */
@Repository
@RequiredArgsConstructor
public class NotebookRepository {

    /** 默认分组：没有指定归属的笔记都落这里。 */
    public static final String DEFAULT_GROUP_ID = NotebookBook.DEFAULT_GROUP_ID;
    public static final String DEFAULT_GROUP_TITLE = NotebookBook.DEFAULT_GROUP_TITLE;

    private final NoteRepository noteRepository;
    private volatile NotebookBook book;


    private NotebookBook book() {
        NotebookBook cached = book;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (book == null) {
                NotebookBook loaded = noteRepository.readMeta(noteRepository.paths().notebooksFile(), NotebookBook.class);
                book = loaded == null ? NotebookBook.empty() : loaded;
            }
            return book;
        }
    }

    private void save(NotebookBook next) {
        noteRepository.writeJson(noteRepository.paths().notebooksFile(), next);
        book = next;
    }

    // -------------------------------------------------------------------- 读

    public List<Dtos.NotebookDto> list(String type) {
        String want = type == null || type.isBlank() ? NotebookBook.TYPE_NOTEBOOK : type;
        List<NotebookBook.Notebook> matched = new ArrayList<>();
        for (NotebookBook.Notebook notebook : book().notebooks()) {
            if (notebook.type().equals(want)) {
                matched.add(notebook);
            }
        }
        matched.sort(Comparator.comparingInt(NotebookBook.Notebook::sortOrder)
                .thenComparing(NotebookBook.Notebook::title, String.CASE_INSENSITIVE_ORDER));
        List<Dtos.NotebookDto> out = new ArrayList<>(matched.size());
        for (NotebookBook.Notebook notebook : matched) {
            out.add(dto(notebook, countNotes(notebook.id())));
        }
        return out;
    }

    public Dtos.NotebookDto find(String id) {
        NotebookBook.Notebook notebook = book().find(id);
        return notebook == null ? null : dto(notebook, countNotes(id));
    }

    private int countNotes(String notebookId) {
        int count = 0;
        for (NoteMeta meta : noteRepository.findAll()) {
            if (notebookId.equals(meta.notebookId())) {
                count++;
            }
        }
        return count;
    }

    private static Dtos.NotebookDto dto(NotebookBook.Notebook notebook, Integer count) {
        return new Dtos.NotebookDto(notebook.id(), notebook.type(), notebook.title(),
                notebook.parentId(), notebook.icon(), notebook.sortOrder(), count,
                notebook.createdAt(), notebook.updatedAt());
    }

    // ---------------------------------------------------------------- 归属硬约束

    public String ensureDefaultGroup() {
        NotebookBook current = book();
        if (current.find(DEFAULT_GROUP_ID) != null) {
            return DEFAULT_GROUP_ID;
        }
        long now = System.currentTimeMillis();
        save(current.with(NotebookBook.Notebook.fresh(DEFAULT_GROUP_ID,
                NotebookBook.TYPE_NOTEBOOK, DEFAULT_GROUP_TITLE, null, "📥", 0, now)));
        return DEFAULT_GROUP_ID;
    }

    public List<String> nodeIdsOf(String notebookId) {
        List<String> out = new ArrayList<>();
        for (NoteMeta meta : noteRepository.findAll()) {
            if (notebookId != null && notebookId.equals(meta.notebookId())) {
                out.add(meta.id());
            }
        }
        return out;
    }

    public int countNotebooksOf(String nodeId) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        return meta != null && meta.notebookId() != null ? 1 : 0;
    }

    /**
     * 把笔记放进某个分组，**并离开原来的分组**（单归属）。
     *
     * <p>界面上的「移到分组」就是它：归属是唯一的，不是累加。
     */
    public void setExclusiveGroup(String nodeId, String notebookId) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        if (meta != null) {
            noteRepository.writeNoteMeta(meta.withNotebook(notebookId));
        }
    }

    // ------------------------------------------------------------ 台账（增删改）

    public String ensureByTitle(String title, String type) {
        String clean = title == null || title.isBlank() ? "未分类" : title.trim();
        String wantType = type == null || type.isBlank() ? NotebookBook.TYPE_NOTEBOOK : type;
        NotebookBook.Notebook existing = book().findByTitle(wantType, clean);
        return existing != null ? existing.id() : create(clean, null, null, wantType);
    }

    public String create(String title, String parentId, String icon, String type) {
        String parent = requireParent(parentId);
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        int max = 0;
        for (NotebookBook.Notebook notebook : book().notebooks()) {
            max = Math.max(max, notebook.sortOrder());
        }
        save(book().with(NotebookBook.Notebook.fresh(id,
                type == null || type.isBlank() ? NotebookBook.TYPE_NOTEBOOK : type,
                title == null || title.isBlank() ? "新建笔记本" : title.trim(),
                parent, icon, max + 1, now)));
        return id;
    }

    /**
     * 全量替换：四个字段一律以本次请求为准；{@code parentId} 传 {@code null} 就是移到顶层——
     * 没有"没提到的字段保持不变"这个语义，调用方从台账当前值出发构造完整请求。
     */
    public Dtos.NotebookDto update(String id, String title, String parentId, String icon,
                                   Integer sortOrder) {
        NotebookBook.Notebook current = book().find(id);
        if (current == null) {
            throw new IllegalArgumentException("分组不存在：" + id);
        }
        if (DEFAULT_GROUP_ID.equals(id) && parentId != null) {
            throw new IllegalArgumentException(
                    "默认分组不能移到别的分组下：它是无归属笔记的落脚点");
        }
        String parent = requireParent(parentId);
        assertNoCycle(id, parent);
        save(book().with(new NotebookBook.Notebook(current.v(), id, current.type(),
                title == null || title.isBlank() ? current.title() : title.trim(),
                parent,
                icon == null ? current.icon() : icon,
                sortOrder == null ? current.sortOrder() : sortOrder,
                current.createdAt(), System.currentTimeMillis())));
        return find(id);
    }

    /** 父级归一 + 存在性校验：null/空白 = 顶层；否则必须指向真实存在的分组。 */
    private String requireParent(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        String parent = parentId.trim();
        if (book().find(parent) == null) {
            throw new IllegalArgumentException("目标分组不存在：" + parent);
        }
        return parent;
    }

    /** 新父级不能是自己或自己的后代，否则那棵子树会从树上脱落。 */
    private void assertNoCycle(String id, String parent) {
        String cursor = parent;
        while (cursor != null) {
            if (cursor.equals(id)) {
                throw new IllegalArgumentException("不能把分组移到它自己或它的子分组下面");
            }
            NotebookBook.Notebook next = book().find(cursor);
            cursor = next == null ? null : next.parentId();
        }
    }

    /** 删分组：其中的笔记回默认分组，子分组上提到被删分组的父级（不连坐、不留孤儿）。 */
    public void delete(String id) {
        if (id == null) {
            return;
        }
        ensureDefaultGroup();
        NotebookBook.Notebook gone = book().find(id);
        String fallbackParent = gone == null ? null : gone.parentId();
        for (NoteMeta meta : noteRepository.findAll()) {
            if (id.equals(meta.notebookId())) {
                noteRepository.writeNoteMeta(meta.withNotebook(DEFAULT_GROUP_ID));
            }
        }
        NotebookBook next = book().without(id);
        List<NotebookBook.Notebook> reparented = new ArrayList<>();
        for (NotebookBook.Notebook notebook : next.notebooks()) {
            reparented.add(id.equals(notebook.parentId())
                    ? notebook.withParent(fallbackParent) : notebook);
        }
        save(new NotebookBook(next.v(), reparented));
    }

    public void setNodeNotebooks(String nodeId, List<String> notebookIds, boolean exclusive) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        if (meta == null) {
            return;
        }
        if (notebookIds == null || notebookIds.isEmpty()) {
            if (exclusive) {
                noteRepository.writeNoteMeta(meta.withNotebook(DEFAULT_GROUP_ID));
            }
            return;
        }
        for (String notebookId : notebookIds) {
            if (notebookId != null && !notebookId.isBlank()) {
                noteRepository.writeNoteMeta(meta.withNotebook(notebookId));
                return;
            }
        }
    }

    /** 单归属下「加入分组」就是「属于这个分组」：没有第二处归属可加。 */
    public void addNodeToNotebook(String nodeId, String notebookId) {
        setExclusiveGroup(nodeId, notebookId);
    }

    public List<Dtos.NotebookDto> notebooksOf(String nodeId) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        if (meta == null || meta.notebookId() == null) {
            return List.of();
        }
        NotebookBook.Notebook notebook = book().find(meta.notebookId());
        if (notebook == null) {
            return List.of();
        }
        return List.of(new Dtos.NotebookDto(notebook.id(), notebook.type(), notebook.title(),
                notebook.parentId(), notebook.icon(), notebook.sortOrder(), null,
                notebook.createdAt(), notebook.updatedAt()));
    }

}
