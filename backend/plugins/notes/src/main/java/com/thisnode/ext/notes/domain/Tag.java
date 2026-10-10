package com.thisnode.ext.notes.domain;

/**
 * 一个标签自己的属性（名称 / 颜色 / 建立与更新时间）。
 *
 * <p>台账 {@link TagBook} 里装的是它们的清单；**归属不在这里** —— 一篇笔记有哪些标签写在它自己
 * meta 的 {@code tags} 里。标签的身份就是它规范化之后的名字。
 */
public record Tag(int v, String name, String color, long createdAt, long updatedAt) {

    public static final int SCHEMA = 1;

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
