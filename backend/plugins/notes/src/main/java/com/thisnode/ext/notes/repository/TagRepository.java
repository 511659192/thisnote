package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.domain.Tag;
import com.thisnode.ext.notes.domain.TagBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 标签：**台账在 {@code .notes/tags.json}**（{@link TagBook}，一个文件装全部标签），
 * 而**归属仍写在各自笔记 meta 的 {@code tags} 里**。
 *
 * <p>这样分的好处：给一篇笔记加/减标签只改那一个笔记文件；标签自己的属性（颜色、建立时间）
 * 只有一份台账；而列表页要的标签名直接从内存索引里的笔记 meta 取，既不碰台账也不碰正文。
 *
 * <p>标签的身份就是它规范化之后的名字。
 */
@Repository
@RequiredArgsConstructor
public class TagRepository {

    private static final Logger log = LoggerFactory.getLogger(TagRepository.class);

    private final NoteRepository noteRepository;

    /** 台账缓存：读一次就够（与分组台账同构）。 */
    private volatile TagBook book;

    // ------------------------------------------------------------------ 台账读写

    private Path tagsFile() {
        return noteRepository.paths().tagsFile();
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
        if (!Files.isRegularFile(file)) {
            return TagBook.empty();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return TagBook.empty();
            }
            return JSON.parseObject(raw, TagBook.class);
        } catch (IOException | RuntimeException e) {
            log.warn("标签台账无法读取：{}（{}）", noteRepository.paths().relative(file), e.toString());
            return TagBook.empty();
        }
    }

    private void save(TagBook next) {
        noteRepository.writeJson(tagsFile(), next);
        book = next;
    }

    /** 台账 + 「只在笔记 meta 里出现、还没登记过」的标签；只在内存里合并。 */
    private TagBook withNoteTags(TagBook source) {
        TagBook merged = source;
        long now = System.currentTimeMillis();
        for (NoteMeta meta : noteRepository.findAll()) {
            for (String name : meta.tags()) {
                if (merged.find(name) == null) {
                    merged = merged.with(Tag.fresh(name, null, now));
                }
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ 登记

    /** 「建标签」：名字规范化并登记进台账（已经有的什么都不做）。 */
    public String ensure(String rawName) {
        String name = normalize(rawName);
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

    private void registerAll(List<String> names) {
        TagBook current = book();
        TagBook next = current;
        long now = System.currentTimeMillis();
        for (String name : names) {
            if (next.find(name) == null) {
                next = next.with(Tag.fresh(name, null, now));
            }
        }
        if (next != current) {
            save(next);
        }
    }

    public static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        String n = rawName.trim();
        while (n.startsWith("#")) {
            n = n.substring(1).trim();
        }
        n = n.replaceAll("\\s+", " ");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    // ------------------------------------------------------------------ 笔记的标签归属

    /** Replace all tags of a node. */
    public void setNodeTags(String nodeId, List<String> names) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        if (meta == null) {
            return;
        }
        List<String> clean = cleaned(names);
        noteRepository.writeNoteMeta(meta.withTags(clean));
        registerAll(clean);
    }

    public void addNodeTag(String nodeId, String name) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        String clean = normalize(name);
        if (meta == null || clean.isEmpty()) {
            return;
        }
        if (!contains(meta.tags(), clean)) {
            List<String> next = new ArrayList<>(meta.tags());
            next.add(clean);
            noteRepository.writeNoteMeta(meta.withTags(next));
        }
        register(clean);
    }

    public void removeNodeTag(String nodeId, String name) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        String clean = normalize(name);
        if (meta == null || clean.isEmpty()) {
            return;
        }
        List<String> next = new ArrayList<>();
        for (String tag : meta.tags()) {
            if (!tag.equalsIgnoreCase(clean)) {
                next.add(tag);
            }
        }
        noteRepository.writeNoteMeta(meta.withTags(next));
    }

    // ------------------------------------------------------------------ 查询

    public List<Dtos.TagDto> tagsOf(String nodeId) {
        NoteMeta meta = noteRepository.getOne(nodeId);
        return meta == null ? List.of() : toDtos(meta.tags(), book());
    }

    /** Tag *names* for many nodes —— 只读笔记 meta，列表/搜索/导出都用它。 */
    public Map<String, List<String>> tagNamesOfAll(List<String> nodeIds) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (nodeIds == null) {
            return map;
        }
        for (String id : nodeIds) {
            NoteMeta meta = noteRepository.getOne(id);
            map.put(id, meta == null ? List.of() : sorted(meta.tags()));
        }
        return map;
    }

    /**
     * 标签清单（侧栏标签云）：**台账里的标签都在**（含还没被任何笔记用到的、计数为 0 的），
     * 外加只在笔记 meta 里出现、尚未登记的。计数从笔记 meta 现算（一次遍历）。
     */
    public List<Dtos.TagDto> listWithCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (NoteMeta meta : noteRepository.findAll()) {
            for (String tag : meta.tags()) {
                counts.merge(tag.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        List<Dtos.TagDto> out = new ArrayList<>();
        for (Tag tag : withNoteTags(book()).tags()) {
            int count = counts.getOrDefault(tag.name().toLowerCase(Locale.ROOT), 0);
            out.add(new Dtos.TagDto(tag.name(), tag.name(), tag.color(), count));
        }
        out.sort(Comparator.comparingInt(Dtos.TagDto::count).reversed()
                .thenComparing(Dtos.TagDto::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ------------------------------------------------------------------ 改名 / 颜色 / 删除

    /** 改名：台账条目改名（保留颜色与建立时间）+ 所有笔记 meta 里的归属跟着改。 */
    public void rename(String id, String newName) {
        String from = normalize(id);
        String to = normalize(newName);
        if (from.isEmpty() || to.isEmpty() || from.equalsIgnoreCase(to)) {
            return;
        }
        for (NoteMeta meta : noteRepository.findAll()) {
            List<String> next = new ArrayList<>();
            boolean changed = false;
            for (String tag : meta.tags()) {
                if (tag.equalsIgnoreCase(from)) {
                    next.add(to);
                    changed = true;
                } else {
                    next.add(tag);
                }
            }
            if (changed) {
                noteRepository.writeNoteMeta(meta.withTags(cleaned(next)));
            }
        }
        long now = System.currentTimeMillis();
        TagBook current = book();
        Tag existing = current.find(from);
        TagBook next = existing == null
                ? current.with(Tag.fresh(to, null, now))
                : current.without(from).with(existing.renamed(to, now));
        save(next);
    }

    public void setColor(String id, String color) {
        String name = normalize(id);
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

    /** 删除标签：台账删条目 + 所有笔记 meta 里的归属一起清掉。 */
    public void rebuildFtsTags() {
        // 全文索引已换成内存实现：没有独立的标签索引列需要刷新。
    }

    // ------------------------------------------------------------------ helpers

    private List<Dtos.TagDto> toDtos(List<String> names, TagBook current) {
        List<Dtos.TagDto> out = new ArrayList<>();
        for (String name : sorted(names)) {
            Tag tag = current.find(name);
            out.add(new Dtos.TagDto(name, name, tag == null ? null : tag.color(), null));
        }
        return out;
    }

    private static List<String> cleaned(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String raw : names) {
            String name = normalize(raw);
            if (!name.isEmpty() && !contains(out, name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static boolean contains(List<String> names, String name) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sorted(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
