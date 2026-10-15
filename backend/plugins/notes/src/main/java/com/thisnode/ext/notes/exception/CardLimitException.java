package com.thisnode.ext.notes.exception;

/** The requested card would exceed the per-note card limit. */
public class CardLimitException extends com.thisnode.plugin.api.exception.CardLimitException {

    public CardLimitException(int limit) {
        super("每篇笔记最多 " + limit + " 张卡片，无法继续新增。请新建一篇笔记。", limit);
    }
}
