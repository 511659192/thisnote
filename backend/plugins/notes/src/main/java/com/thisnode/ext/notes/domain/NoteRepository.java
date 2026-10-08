package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.store.NoteStore;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.ext.notes.util.Markdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 笔记的读写（**单一职责：只认笔记域**）。
 *
 * <p>卡片的事一概不在这里：读正文走 {@link NoteCompositionService#noteMarkdown}，建首卡走
 * {@link NoteCompositionService#createNote}，删笔记走 {@link NoteCompositionService#removeNote}。
 * 于是这个仓储里不再出现 {@code BlockMeta}，也不再直接读写卡片文件。
 *
 * <p>与数据库时代最大的不同：笔记**没有自己的正文存储**。{@code contentMd} 是读时由卡片按清单
 * 顺序拼出来的派生值，写正文等于写卡片。因此不存在"更新正文"的独立路径，也就不可能出现笔记
 * 正文与卡片两份内容不一致。
 *
 * <p>方法签名刻意与旧的 JdbcTemplate 版本保持一致：上层 {@code NoteService}、控制器与前端不需要
 * 知道下面的存储换了。
 */
@Repository
@RequiredArgsConstructor
public class NoteRepository {

    private final NoteStore noteStore;
    private final NoteCompositionService composition;

    /**
     * 元数据 → Note 的**轻量视图**：只带 meta 的基本字段，不带正文。
     *
     * <p>列表/计数路径绝不能读卡片 —— 那会让"打开列表"退化成"读全库卡片"。需要真正文的调用方走
     * {@link #findById(String)}。
     */
    private Note toNode(NoteMeta meta) {
        Note n = new Note();
        n.id = meta.id();
        n.title = meta.title();
        n.type = meta.type();
        n.source = meta.source();
        n.status = meta.status();
        n.archived = meta.archived();
        n.deleted = meta.deleted();
        n.deletedAt = meta.deletedAt();
        n.dailyDate = meta.dailyDate();
        n.createdAt = meta.createdAt();
        n.updatedAt = meta.updatedAt();
        return n;
    }

    /** 带正文的视图（单篇读取用）：会按需读这篇笔记的卡片。 */
    private Note toNodeWithContent(NoteMeta meta) {
        Note n = toNode(meta);
        n.contentMd = composition.noteMarkdown(meta.id());
        return n;
    }

    /** 标题兜底：没写标题就用正文首个标题，再没有才叫"无标题笔记"（旧 prepare() 的规则）。 */
    private static String titleOf(Note n) {
        if (n.title != null && !n.title.isBlank()) {
            return n.title;
        }
        String fromHeading = Markdown.firstHeading(n.contentMd);
        return fromHeading != null ? fromHeading : "无标题笔记";
    }

    public Note findById(String id) {
        NoteMeta meta = noteStore.note(id);
        return meta == null ? null : toNodeWithContent(meta);
    }

    public List<Note> findByIds(List<String> ids) {
        List<Note> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            Note n = findById(id);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** Case-insensitive title lookup used by {@code [[link]]} resolution. */
    public List<Note> findByExactTitle(String title) {
        List<Note> out = new ArrayList<>();
        String key = Markdown.normalizeTitle(title);
        if (key.isEmpty()) {
            return out;
        }
        for (NoteMeta meta : noteStore.scanNotes()) {
            if (!meta.deleted() && key.equals(Markdown.normalizeTitle(meta.title()))) {
                out.add(toNode(meta));
                if (out.size() >= 2) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 建一篇笔记。
     *
     * <p>初始正文必须在这里就落成一张卡：文件模型下笔记没有正文存储，只有卡片有内容，少这一步就
     * 等于把调用方给的正文丢掉（导入、每日笔记、从悬空链接建笔记都会走这里）。建卡由组合层完成。
     */
    public String insert(Note n) {
        if (n.id == null) {
            n.id = Ids.newId();
        }
        long now = System.currentTimeMillis();
        n.createdAt = now;
        n.updatedAt = now;
        composition.createNote(NoteMeta.fresh(n.id, titleOf(n), n.type, n.dailyDate, n.source,
                n.status, null, now), n.contentMd);
        return n.id;
    }

    /**
     * 更新元数据，并在正文与当前拼接结果不一致时整篇替换正文。
     *
     * <p>正常编辑路径下两者必然相等（正文就是从卡片拼出来的，见
     * {@code NoteBlockService.syncNoteFromBlocks}），所以这里通常什么都不做；只有导入 / 脚本
     * 直接改正文时才会走到替换。
     */
    public void update(Note n) {
        NoteMeta current = noteStore.note(n.id);
        if (current == null) {
            return;
        }
        String derived = composition.noteMarkdown(n.id);
        if (!Objects.equals(derived, n.contentMd == null ? "" : n.contentMd)) {
            composition.replaceNoteContent(n.id, n.contentMd);
            // 替换可能新建/改写卡片清单，重新读一次再写元数据，避免把刚写入的清单覆盖掉。
            NoteMeta refreshed = noteStore.note(n.id);
            current = refreshed == null ? current : refreshed;
        }
        long now = System.currentTimeMillis();
        noteStore.writeNoteMeta(current.withTitle(titleOf(n))
                .withType(n.type)
                .withDailyDate(n.dailyDate)
                .withSourceStatus(n.source, n.status)
                .withArchived(n.archived)
                .withDeleted(n.deleted)
                .touched(now));
        n.updatedAt = now;
    }

    public void touch(String id) {
        NoteMeta current = noteStore.note(id);
        if (current != null) {
            noteStore.writeNoteMeta(current.touched(System.currentTimeMillis()));
        }
    }

    public void setDeleted(String id, boolean deleted) {
        NoteMeta current = noteStore.note(id);
        if (current != null) {
            noteStore.writeNoteMeta(current.withDeleted(deleted).touched(System.currentTimeMillis()));
        }
    }

    public void setArchived(String id, boolean archived) {
        NoteMeta current = noteStore.note(id);
        if (current != null) {
            noteStore.writeNoteMeta(current.withArchived(archived).touched(System.currentTimeMillis()));
        }
    }

    /** 硬删除：卡片 md、sidecar 与笔记 meta 一起走（由组合层负责）。 */
    public void deleteHard(String id) {
        composition.removeNote(id);
    }

    public void deleteHardAll(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            composition.removeNote(id);
        }
    }

    /** Permanently remove everything in the trash. */
    public int emptyTrash() {
        List<String> doomed = new ArrayList<>();
        for (NoteMeta meta : noteStore.scanNotes()) {
            if (meta.deleted()) {
                doomed.add(meta.id());
            }
        }
        for (String id : doomed) {
            composition.removeNote(id);
        }
        return doomed.size();
    }

    public long countAll() {
        return noteStore.scanNotes().size();
    }

    public long countActive() {
        return noteStore.scanNotes().stream().filter(meta -> !meta.deleted()).count();
    }

    public Note findByDailyDate(String date) {
        if (date == null) {
            return null;
        }
        for (NoteMeta meta : noteStore.scanNotes()) {
            if (!meta.deleted() && date.equals(meta.dailyDate())) {
                return toNode(meta);
            }
        }
        return null;
    }

    public List<Note> findAllActive() {
        List<Note> out = new ArrayList<>();
        for (NoteMeta meta : noteStore.scanNotes()) {
            if (!meta.deleted()) {
                out.add(toNode(meta));
            }
        }
        return out;
    }

    /** 全部笔记，含回收站里的那些（{@link #findAllActive()} 会排除它们）。 */
    public List<Note> findAll() {
        List<Note> out = new ArrayList<>();
        for (NoteMeta meta : noteStore.scanNotes()) {
            out.add(toNode(meta));
        }
        return out;
    }
}
