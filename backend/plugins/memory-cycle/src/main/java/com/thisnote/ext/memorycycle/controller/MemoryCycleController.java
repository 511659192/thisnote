package com.thisnote.ext.memorycycle.controller;

import com.thisnote.ext.memorycycle.model.CardSchedule;
import com.thisnote.ext.memorycycle.model.ScheduleHistoryEntry;
import com.thisnote.ext.memorycycle.model.request.CardStateBatchRequest;
import com.thisnote.ext.memorycycle.model.request.ReviewRequest;
import com.thisnote.ext.memorycycle.model.view.CardStateView;
import com.thisnote.ext.memorycycle.model.view.DueCardView;
import com.thisnote.ext.memorycycle.model.view.ScheduleView;
import com.thisnote.ext.memorycycle.service.MemoryCycleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The memory-cycle plugin's own surface, namespaced under {@code /api/plugins/memory-cycle}.
 */
@RestController
@RequestMapping("/api/plugins/memory-cycle")
public class MemoryCycleController {

    private final MemoryCycleService memoryCycleService;

    public MemoryCycleController(MemoryCycleService memoryCycleService) {
        this.memoryCycleService = memoryCycleService;
    }

    /**
     * State for many cards at once, so a page of cards costs one request.
     */
    @GetMapping("/get-cards")
    public List<CardStateView> cards(@RequestParam List<String> ids) {
        return memoryCycleService.cardStates(ids).stream().map(this::cardStateView).toList();
    }

    @GetMapping("/get-card")
    public CardStateView card(@RequestParam String cardId) {
        return cardStateView(memoryCycleService.cardState(cardId));
    }

    /**
     * 某张卡片的复习历史（从旧到新）。
     */
    @GetMapping("/get-history")
    public List<ScheduleHistoryEntry> history(@RequestParam String cardId) {
        return memoryCycleService.history(cardId);
    }

    /**
     * 批量取复习历史（卡片 id → 记录），给列表页一次请求用。
     */
    @GetMapping("/get-histories")
    public Map<String, List<ScheduleHistoryEntry>> histories(@RequestParam List<String> ids) {
        return memoryCycleService.histories(ids);
    }

    @PostMapping("/update-state")
    public Map<String, Object> setState(@RequestParam String cardId,
                                        @RequestParam boolean value) {
        memoryCycleService.setEnabled(cardId, value);
        return Map.of("enabled", value);
    }

    /**
     * 批量设置卡片状态：给「按笔记本开启复习」用。
     */
    @PostMapping("/update-state-batch")
    public Map<String, Object> setStateBatch(@RequestBody CardStateBatchRequest request) {
        int updated = 0;
        for (String cardId : request.cardIds()) {
            if (cardId == null || cardId.isBlank()) continue;
            memoryCycleService.setEnabled(cardId, request.value());
            updated++;
        }
        return Map.of("updated", updated);
    }

    /**
     * Grade a card: 1=忘记 2=困难 3=良好 4=简单.
     */
    @PostMapping("/review")
    public CardStateView review(@RequestParam String cardId,
                                @RequestBody ReviewRequest request) {
        if (request.grade() == null) {
            throw new IllegalArgumentException("缺少 grade");
        }
        return cardStateView(memoryCycleService.review(cardId, request.grade()));
    }

    /**
     * Due cards across all notes (memory cycle enabled only).
     */
    @GetMapping("/get-due")
    public List<DueCardView> due(@RequestParam(defaultValue = "50") int limit) {
        return memoryCycleService.due(limit).stream().map(this::dueView).toList();
    }

    /**
     * 今日到期（自然天）的卡片数量，给侧栏徽标用。
     */
    @GetMapping("/get-due-count")
    public Map<String, Integer> dueCount() {
        return Map.of("count", memoryCycleService.dueTodayCount());
    }

    /**
     * 删掉一张卡片的排期条目。
     *
     * <p>给「孤儿清理」用：复习页在核心那边查不到某条排期对应的卡片（卡片被删、或库被外部
     * 重建过），就顺手调这里清掉。本插件不读笔记数据，自己没有别的办法判断卡片还在不在 ——
     * 正常删除路径由主容器的 {@code CardChangedEvent} 负责，这里是兜底。
     */
    @PostMapping("/delete-schedule")
    public Map<String, Object> forget(@RequestParam String cardId) {
        return Map.of("cardId", cardId, "removed", memoryCycleService.delete(cardId));
    }

    private CardStateView cardStateView(CardSchedule schedule) {
        return new CardStateView(schedule.getCardId(), schedule.isEnabled(), scheduleView(schedule), schedule.getGradePreview());
    }

    private ScheduleView scheduleView(CardSchedule schedule) {
        long now = System.currentTimeMillis();
        long dueAt = 0;
        if (schedule.isEnabled()) {
            dueAt = MemoryCycleService.isShortPhase(schedule.getState()) ? now : schedule.getDueAt().getTime();
        }
        Long lastReviewedAt = schedule.getLastReviewedAt() == null ? null : schedule.getLastReviewedAt().getTime();
        return new ScheduleView(schedule.getCardId(),
                                schedule.getState(),
                                schedule.getLevel(),
                                dueAt,
                                lastReviewedAt,
                                schedule.getReps(),
                                schedule.getLapses(),
                                schedule.isDue());
    }

    private DueCardView dueView(CardSchedule schedule) {
        return new DueCardView(schedule.getCardId(),
                               schedule.getDueAt().getTime(),
                               schedule.getState(),
                               schedule.getLevel(),
                               schedule.getGradePreview());
    }

}
