package com.thisnode.plugin.api.event;

import com.thisnode.plugin.api.notes.dto.CardDTO;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 只返回 since 之后有改动（写入或迁入）的卡片。
 */
public record CardQueryEvent(CompletableFuture<List<CardDTO>> result, Instant since) {
}
