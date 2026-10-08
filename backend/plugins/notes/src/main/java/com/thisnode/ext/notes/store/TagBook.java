package com.thisnode.ext.notes.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签台账：**一个文件装全部标签**（名称 / 颜色 / 创建与更新时间）。
 *
 * <p>与分组台账（{@link NotebookBook}）同构：**归属不在这里** —— 一篇笔记有哪些标签写在它自己的
 * meta 的 {@code tags} 里，所以给一篇笔记加标签只改那一个文件；台账只回答「标签自己长什么样」。
 * 标签的身份就是它规范化之后的名字：稳定、可读、跨笔记天然去重。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagBook(int v, List<Tag> tags) {

    public static final int SCHEMA = 1;

    public TagBook {
        v = v <= 0 ? SCHEMA : v;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static TagBook empty() {
        return new TagBook(SCHEMA, List.of());
    }

    /** 按名字找（忽略大小写）。 */
    public Tag find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Tag tag : tags) {
            if (tag.name().equalsIgnoreCase(name)) {
                return tag;
            }
        }
        return null;
    }

    /** 有就替换、没有就追加。 */
    public TagBook with(Tag tag) {
        List<Tag> next = new ArrayList<>();
        boolean replaced = false;
        for (Tag existing : tags) {
            if (existing.name().equalsIgnoreCase(tag.name())) {
                next.add(tag);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(tag);
        }
        return new TagBook(v, next);
    }

    public TagBook without(String name) {
        List<Tag> next = new ArrayList<>();
        for (Tag existing : tags) {
            if (name == null || !existing.name().equalsIgnoreCase(name)) {
                next.add(existing);
            }
        }
        return new TagBook(v, next);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(int v, String name, String color, long createdAt, long updatedAt) {

        public Tag {
            v = v <= 0 ? SCHEMA : v;
            name = name == null ? "" : name;
        }

        public static Tag fresh(String name, String color, long now) {
            return new Tag(SCHEMA, name, color, now, now);
        }

        public Tag withColor(String ncolor, long now) {
            return new Tag(v, name, ncolor, createdAt, now);
        }

        public Tag renamed(String nname, long now) {
            return new Tag(v, nname, color, createdAt, now);
        }
    }
}
