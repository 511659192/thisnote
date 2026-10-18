package com.thisnode.ext.memorycycle.model;

import lombok.Data;

/**
 * 一次复习记录：某张卡片在什么时间、以什么评分、排到了下一次什么时候。
 *
 * <p>只追加、不修改；每张卡片保留最近 {@code ScheduleHistoryRepository.MAX_ENTRIES_PER_CARD} 条。
 */
@Data
public class ScheduleHistoryEntry {

    private String cardId;

    /** 复习时间（epoch 毫秒）。 */
    private long reviewedAt;

    /** 评分 1..4；0 表示「开启记忆曲线」这条初始记录（不是评分）。 */
    private int grade;

    /** 评分后的状态：new / learning / review / relearning。 */
    private String state;

    /** 评分后的等级。 */
    private int level;

    /** 评分后的下次复习时间（epoch 毫秒）。 */
    private long dueAt;
}
