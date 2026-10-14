package com.thisnode.ext.memorycycle;

import com.thisnode.ext.memorycycle.api.dto.ReviewRequest;
import com.thisnode.ext.memorycycle.api.dto.ScheduleView;
import com.thisnode.ext.memorycycle.app.CardSchedule;
import com.thisnode.ext.memorycycle.app.MemoryCycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The memory-cycle plugin's own surface, namespaced under {@code /api/plugins/memory-cycle}.
 */
@RestController
@RequestMapping("/api/plugins/memory-cycle")
public class MemoryCycleController {

    private final MemoryCycleService srs;

    public MemoryCycleController(MemoryCycleService srs) {
        this.srs = srs;
    }

    /** State for many cards at once, so a page of cards costs one request. */
    @GetMapping("/get-cards")
    public List<Map<String, Object>> cards(@RequestParam List<String> ids) {
        return srs.cardStates(ids).stream().map(this::cardStateView).toList();
    }

    @GetMapping("/get-card")
    public Map<String, Object> card(@RequestParam String cardId) {
        return cardStateView(srs.cardState(cardId));
    }

    @PostMapping("/update-state")
    public Map<String, Object> setState(@RequestParam String cardId,
                                       @RequestParam boolean value) {
        srs.setEnabled(cardId, value);
        return Map.of("enabled", value);
    }

    /** Grade a card: 1=忘记 2=困难 3=良好 4=简单. */
    @PostMapping("/review")
    public Map<String, Object> review(@RequestParam String cardId,
                                      @RequestBody ReviewRequest request) {
        if (request == null || request.grade() == null) {
            throw new IllegalArgumentException("缺少 grade");
        }
        srs.review(cardId, request.grade());
        return cardStateView(srs.cardState(cardId));
    }

    /** Due cards across all notes (memory cycle enabled only). */
    @GetMapping("/get-due")
    public List<Map<String, Object>> due(@RequestParam(defaultValue = "50") int limit) {
        return srs.due(limit).stream().map(this::dueView).toList();
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
        return Map.of("cardId", cardId, "removed", srs.delete(cardId));
    }

    private Map<String, Object> cardStateView(CardSchedule schedule) {
        return Map.of("cardId",
                      schedule.getCardId(),
                      "enabled",
                      schedule.isEnabled(),
                      "schedule",
                      scheduleView(schedule),
                      "gradePreview",
                      schedule.getGradePreview());
    }

    private ScheduleView scheduleView(CardSchedule schedule) {
        long now = System.currentTimeMillis();
        long dueAt = 0;
        if (schedule.isEnabled()) {
            dueAt = isShortPhase(schedule.getState()) ? now : schedule.getDueAt().getTime();
        }
        Long lastReviewedAt = schedule.getLastReviewedAt() == null
                ? null : schedule.getLastReviewedAt().getTime();
        return new ScheduleView(schedule.getCardId(),
                                schedule.getState(),
                                schedule.getLevel(),
                                dueAt,
                                lastReviewedAt,
                                schedule.getReps(),
                                schedule.getLapses(),
                                schedule.isDue());
    }

    private boolean isShortPhase(String state) {
        return "new".equals(state) || "learning".equals(state) || "relearning".equals(state);
    }

    private Map<String, Object> dueView(CardSchedule schedule) {
        return Map.of("cardId",
                      schedule.getCardId(),
                      "dueAt",
                      schedule.getDueAt().getTime(),
                      "state",
                      schedule.getState(),
                      "level",
                      schedule.getLevel(),
                      "gradePreview",
                      schedule.getGradePreview());
    }

}
