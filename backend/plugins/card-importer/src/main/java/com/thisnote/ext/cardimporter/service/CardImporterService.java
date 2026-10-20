package com.thisnote.ext.cardimporter.service;

import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.CardQueryEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Service
public class CardImporterService {

    private static final Logger log = LoggerFactory.getLogger(CardImporterService.class);
    private static final long SCAN_INTERVAL_MILLIS = 10_000;
    private static final String DEFAULT_BOOK_ID = "grp-default";

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    public void scan() {
        try {
            requestCardImports();
        } catch (Exception e) {
            log.error("扫描 notes/cards 提交导入申请失败", e);
        }
    }

    /**
     * 将没有卡片索引条目的 md（无主卡片）创建申请交给 Notes 处理。
     */
    private void requestCardImports() {
        CompletableFuture<List<CardDTO>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new CardQueryEvent(result));
        List<CardDTO> cards = result.join();
        if (cards.isEmpty()) return;

        cards.forEach(card -> mainEventPublisher.publishEvent(
                CardChangedEvent.requestCardCreate(card, title(card.sourceFileName()), DEFAULT_BOOK_ID)));
    }


    private String title(String fileName) {
        return fileName.substring(0, fileName.length() - ".md".length());
    }
}
