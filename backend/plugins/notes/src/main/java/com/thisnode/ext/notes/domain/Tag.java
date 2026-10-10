package com.thisnode.ext.notes.domain;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

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

    /** 规范化标签名：去掉前导 {@code #}、压缩空白并限制为 64 个字符。 */
    public static String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim();
        while (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        normalized = normalized.replaceAll("\\\\s+", " ");
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    /** 规范化并按忽略大小写去重，保留首次出现的写法。 */
    public static List<String> cleanNames(List<String> names) {
        List<String> result = new ArrayList<>();
        if (names == null) {
            return result;
        }
        for (String rawName : names) {
            String normalized = normalizeName(rawName);
            if (!normalized.isEmpty()
                    && !CollUtil.contains(result, existing -> StrUtil.equalsIgnoreCase(existing, normalized))) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** 按标签名忽略大小写排序，返回新列表。 */
    public static List<String> sortedNames(List<String> names) {
        return names == null ? List.of()
                : CollUtil.sort(new ArrayList<>(names), String.CASE_INSENSITIVE_ORDER);
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
