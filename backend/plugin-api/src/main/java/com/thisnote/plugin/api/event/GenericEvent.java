package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.notes.dto.CardDTO;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 查询「无主卡片」（cards 目录里、卡片索引中没有的 md），由 notes 侧监听填充。
 */
public record GenericEvent(String type, Map<String, Object> param, CompletableFuture<List<CardDTO>> result) {
}
