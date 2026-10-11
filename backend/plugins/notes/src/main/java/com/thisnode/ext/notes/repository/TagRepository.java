package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.domain.Tag;
import com.thisnode.ext.notes.domain.TagBook;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 标签台账：**一个文件装全部标签**（名称 / 颜色 / 建立与更新时间），见 {@link TagBook}。
 *
 * <p>**归属不在这里** —— 一篇笔记有哪些标签写在它自己的 meta 里，那一半归 {@code NoteRepository}。
 * 两个仓储因此互不依赖：需要把两边合起来的视图（清单 + 计数）由 {@code NoteService} 编排。
 *
 * <p>标签的身份就是它规范化之后的名字（规则见 {@link Tag#normalizeName(String)}）。
 */
@Repository
@RequiredArgsConstructor
public class TagRepository {

    private static final Logger log = LoggerFactory.getLogger(TagRepository.class);

    private final NoteFilePaths paths;

    /** 台账缓存：读一次就够（与分组台账同构）。 */
    private volatile TagBook book;

    private Path tagsFile() {
        return paths.tagsFilePath();
    }

    private TagBook book() {
        TagBook cached = book;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (book == null) {
                book = load();
            }
            return book;
        }
    }

    private TagBook load() {
        Path file = tagsFile();
        try {
            TagBook loaded = JsonFileUtils.read(file, TagBook.class);
            return loaded == null ? TagBook.empty() : loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("标签台账无法读取：{}（{}）", paths.relative(file), e.toString());
            return TagBook.empty();
        }
    }

    private void save(TagBook next) {
        JsonFileUtils.write(tagsFile(), next);
        book = next;
    }

    // ------------------------------------------------------------------ 登记

    /** 「建标签」：名字规范化并登记进台账（已经有的什么都不做）。 */
    public String ensure(String rawName) {
        String name = Tag.normalizeName(rawName);
        if (name.isEmpty()) {
            return null;
        }
        register(name);
        return name;
    }

    private void register(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        TagBook current = book();
        if (current.find(name) != null) {
            return;
        }
        save(current.with(Tag.fresh(name, null, System.currentTimeMillis())));
    }

    /** 把一批标签登记进台账（笔记写入标签归属时由服务层调用；已登记的什么都不做）。 */
    public void registerAll(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        TagBook current = book();
        TagBook next = current;
        long now = System.currentTimeMillis();
        for (String raw : names) {
            String name = Tag.normalizeName(raw);
            if (!name.isEmpty() && next.find(name) == null) {
                next = next.with(Tag.fresh(name, null, now));
            }
        }
        if (next != current) {
            save(next);
        }
    }

    // ------------------------------------------------------------------ 查询（台账这一侧）

    /** 台账里的全部标签（含还没被任何笔记用到、计数为 0 的那些）。 */
    public List<Tag> tags() {
        return book().tags();
    }

    /** 某个标签的颜色；没登记过给 {@code null}。 */
    public String colorOf(String name) {
        Tag tag = book().find(Tag.normalizeName(name));
        return tag == null ? null : tag.color();
    }

    // ------------------------------------------------------------------ 颜色 / 改名

    public void setColor(String id, String color) {
        String name = Tag.normalizeName(id);
        if (name.isEmpty()) {
            return;
        }
        String value = color == null || color.isBlank() ? null : color;
        long now = System.currentTimeMillis();
        TagBook current = book();
        Tag existing = current.find(name);
        Tag nextTag = existing == null
                ? Tag.fresh(name, value, now)
                : existing.withColor(value, now);
        save(current.with(nextTag));
    }

    /** 改名（台账这一侧）：条目改名、保留颜色与建立时间；笔记 meta 那一半由服务层编排。 */
    public void rename(String id, String newName) {
        String from = Tag.normalizeName(id);
        String to = Tag.normalizeName(newName);
        if (from.isEmpty() || to.isEmpty() || from.equalsIgnoreCase(to)) {
            return;
        }
        long now = System.currentTimeMillis();
        TagBook current = book();
        Tag existing = current.find(from);
        TagBook next = existing == null
                ? current.with(Tag.fresh(to, null, now))
                : current.without(from).with(existing.renamed(to, now));
        save(next);
    }
}
