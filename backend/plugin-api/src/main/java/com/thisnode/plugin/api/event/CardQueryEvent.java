package com.thisnode.plugin.api.event;

import com.thisnode.plugin.api.notes.dto.CardDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public record CardQueryEvent(CompletableFuture<List<CardDTO>> result) {
}
