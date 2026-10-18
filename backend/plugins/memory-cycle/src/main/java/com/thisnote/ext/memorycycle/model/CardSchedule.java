package com.thisnote.ext.memorycycle.model;

import com.thisnote.ext.memorycycle.service.MemoryCycleService;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.Map;

/**
 * 一张卡片的间隔重复状态（memory-cycle 插件自己的数据，落成 {@code .scheduling.json}）。
 *
 * <p>字段默认值就是「一张还没排期的卡片」的初始状态；状态机在 {@link MemoryCycleService} 里就地推进它，
 * 所以这里是可变对象，而不是 record。
 *
 * <p>访问器由 Lombok 生成（{@code @Getter}/{@code @Setter}）—— 十来个字段的 get/set 全是样板。
 * 不用 {@code @Data}：这是按 id 认身份的实体，自动 equals/hashCode 会把「同一张卡的两次快照」
 * 判成不同对象，是坑。
 */
@Getter
@Setter
public class CardSchedule {

    private String cardId;

    /** new / learning / review / relearning */
    private String state = "new";

    private int level = 1;
    @JSONField(format = "yyyy年MM月dd日 HH:mm:ss")
    private Date dueAt = new Date(0);
    @JSONField(format = "yyyy年MM月dd日 HH:mm:ss")
    private Date lastReviewedAt;
    private int reps;
    private int lapses;

    @JSONField(serialize = false, deserialize = false)
    private boolean enabled;
    @JSONField(serialize = false, deserialize = false)
    private boolean due;
    @JSONField(serialize = false, deserialize = false)
    private Map<Integer, Long> gradePreview;
}
