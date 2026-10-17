package com.thisnode.ext.cardimporter.service;

import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.event.CardQueryEvent;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Service
public class CardImporterService {

    private static final Logger log = LoggerFactory.getLogger(CardImporterService.class);
    private static final long SCAN_INTERVAL_MILLIS = 10_000;
    /**
     * 每轮只回看这段时间内改动过的卡片：卡片多时也不必反复读取整个 cards 目录。
     */
    private static final Duration IMPORT_LOOKBACK = Duration.ofHours(1);
    private static final String DEFAULT_BOOK_ID = "grp-default";

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    public void scan() {
        try {
            requestCardImports(IMPORT_LOOKBACK);
        } catch (RuntimeException e) {
            log.warn("扫描 notes/cards 提交导入申请失败", e);
        }
    }

    /**
     * 将缺少元数据的卡片创建申请交给 Notes 处理。
     * 只处理 lookback 之内有改动（写入或迁入）的卡片。
     */
    private void requestCardImports(Duration lookback) {
        CompletableFuture<List<CardDTO>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new CardQueryEvent(result, Instant.now().minus(lookback)));
        List<CardDTO> cards = result.join();
        if (cards.isEmpty()) return;

        for (CardDTO card : cards) {
            if (card.meta() != null && card.meta().noteId() != null) continue;
            String noteTitle = title(card.sourceFileName());
            mainEventPublisher.publishEvent(CardChangedEvent.requestCardCreate(card, noteTitle, DEFAULT_BOOK_ID));
        }
    }


    private String title(String fileName) {
        return fileName.substring(0, fileName.length() - ".md".length());
    }
}
