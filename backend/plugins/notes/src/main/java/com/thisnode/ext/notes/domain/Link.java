package com.thisnode.ext.notes.domain;

/**
 * 一条链接 —— **出链与入链共用同一个形状**，方向由 {@code from → to} 表达
 * （{@link NoteMeta#outLinks()} 里 {@code from} 是本文，{@link NoteMeta#inLinks()} 里 {@code to} 是本文）。
 *
 * @param from  来源笔记 id
 * @param to    目标笔记 id；{@code null} = 正文里写了 {@code [[...]]} 但目标还没建
 * @param label 显示文字（{@code [[目标|显示文字]]} 的后者）
 * @param raw   {@code [[...]]} 里的**原文目标**：只有出链有。未解析时靠它显示原文与"点一下新建"，
 *              后端 {@code resolvePendingLinks} 也靠它认领后建的笔记
 */
public record Link(String from, String to, String label, String raw) {

    public Link {
        label = label == null ? "" : label;
        raw = raw == null ? "" : raw;
    }

    /** 出链：{@code from} = 本文，{@code raw} = 正文里的原文，{@code to} = 解析到的目标（{@code null} 表示还没建）。 */
    public static Link outbound(String from, String to, String label, String raw) {
        return new Link(from, to, label, raw);
    }

    /** 入链：{@code from} = 来源笔记，{@code to} = 本文。 */
    public static Link inbound(String from, String to, String label) {
        return new Link(from, to, label, "");
    }

    /** 正文里写了、但当时没解析到目标（显示原文与"点击新建"都用 {@code raw}）。 */
    public boolean unresolved() {
        return to == null || to.isBlank();
    }
}
