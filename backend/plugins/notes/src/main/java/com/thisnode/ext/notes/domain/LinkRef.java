package com.thisnode.ext.notes.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 笔记 meta 里缓存的一条出链：写正文时算好，列表页与入链统计都靠它，不必再读正文。
 *
 * <p>{@code target} 保留**原文**（{@code [[目标]]} 里的写法），所以"先写链接、后建笔记"时还能回头
 * 认出它；{@code to} 是当时解析到的笔记 id（解析不到就是 null，此时只计入未解析数）。
 *
 * @param target {@code [[...]]} 里的原文目标（标题或别名）
 * @param label  显示文字（{@code [[目标|显示文字]]} 的后者，可为空）
 * @param to     解析到的笔记 id；{@code null} 表示当时没解析到
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LinkRef(String target, String label, String to) {

    public static LinkRef of(String target, String label, String to) {
        return new LinkRef(target == null ? "" : target, label == null ? "" : label, to);
    }

    /** 解析到之后补上目标（其余不变）。 */
    public LinkRef resolvedTo(String noteId) {
        return new LinkRef(target, label, noteId);
    }
}
