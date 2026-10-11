package com.thisnode.ext.memorycurve;

import com.thisnode.ext.memorycurve.api.dto.ReviewRequest;
import com.thisnode.ext.memorycurve.api.dto.ScheduleView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The memory-curve plugin's own surface, namespaced under {@code /api/plugins/memory-curve}.
 */
@RestController
@RequestMapping("/api/plugins/memory-curve")
public class MemoryCurveController {

    private final SrsService srs;

    public MemoryCurveController(SrsService srs) {
        this.srs = srs;
    }

    /** State for many cards at once, so a page of cards costs one request. */
    @GetMapping("/cards")
    public List<Map<String, Object>> cards(@RequestParam List<String> ids) {
        return srs.cardStates(ids);
    }

    @GetMapping("/card")
    public Map<String, Object> card(@RequestParam String cardId) {
        return srs.cardState(cardId);
    }

    /** Grade a card: 1=忘记 2=困难 3=良好 4=简单. */
    @PostMapping("/review")
    public Map<String, Object> review(@RequestParam String cardId,
                                      @RequestBody ReviewRequest request) {
        if (request == null || request.grade() == null) {
            throw new IllegalArgumentException("缺少 grade");
        }
        ScheduleView updated = srs.review(cardId, request.grade());
        return Map.of("enabled", true, "schedule", updated);
    }

    /** Due cards across all notes (memory curve enabled only). */
    @GetMapping("/due")
    public List<Map<String, Object>> due(@RequestParam(defaultValue = "50") int limit) {
        return srs.due(limit);
    }

    /**
     * 删掉一张卡片的排期条目。
     *
     * <p>给「孤儿清理」用：复习页在核心那边查不到某条排期对应的卡片（卡片被删、或库被外部
     * 重建过），就顺手调这里清掉。本插件不读笔记数据，自己没有别的办法判断卡片还在不在 ——
     * 正常删除路径由主容器的 {@code CardChangedEvent} 负责，这里是兜底。
     */
    @PostMapping("/forget")
    public Map<String, Object> forget(@RequestParam String cardId) {
        return Map.of("cardId", cardId, "removed", srs.forget(cardId));
    }

}
