package com.thisnode.ext.notes.domain;

/**
 * 笔记清单里的一张卡片：{@code id} + 文件路径。
 *
 * <p><b>为什么清单里要带 id：</b>卡片文件是懒加载的——不打开文件就不知道它的 id，而 API 到处都要
 * 用 cardId 反查归属（拖动、改内容、删除都以 cardId 为入口）。把 id 记在笔记清单里，反查就变成
 * 一次内存 map 查询，不需要为了拿一个 id 去打开文件。
 *
 * @param id   卡片 id（稳定身份；文件名变了不影响它）
 * @param file 卡片文件相对笔记目录根目录的路径；顺序由它在清单里的位置决定
 */
public record CardRef(String id, String file) {
}
