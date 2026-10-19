package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;
import com.thisnote.plugin.api.notes.dto.CardDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 查询「无主卡片」（cards 目录里、卡片索引中没有的 md），由 notes 侧监听填充。
 */
public record CardQueryEvent(CompletableFuture<List<CardDTO>> result)
        implements PluginQuery<List<CardDTO>> {
}
