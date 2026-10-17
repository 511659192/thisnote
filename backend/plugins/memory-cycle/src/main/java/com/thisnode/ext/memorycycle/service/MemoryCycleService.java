package com.thisnode.ext.memorycycle.service;

import com.thisnode.ext.memorycycle.model.CardSchedule;
import com.thisnode.ext.memorycycle.repository.CardScheduleRepository;
import com.thisnode.plugin.api.event.MemoryCycleStateChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class MemoryCycleService {

    private static final int[][] INTERVAL_DAYS_BY_LEVEL = {{1, 2, 3}, {2, 3, 5}, {3, 5, 8}, {5, 8, 13}, {8, 13, 21}, {13, 21, 34}, {21, 34, 60}, {34, 48, 60}, {47, 60, 60}, {60, 60, 60}};

    private static final long DAY_MS = 86_400_000L;

    private final CardScheduleRepository scheduleRepository;
    private ApplicationEventPublisher mainEventPublisher;

    public MemoryCycleService(ApplicationEventPublisher mainEventPublisher,
                              CardScheduleRepository scheduleRepository) {
        this.mainEventPublisher = mainEventPublisher;
        this.scheduleRepository = scheduleRepository;
    }

    private static boolean isShortPhase(String state) {
        return "new".equals(state) || "learning".equals(state) || "relearning".equals(state);
    }

    /**
     * Enabled + schedule for one card. Schedule is {@code null} until the first review.
     */
    public synchronized CardSchedule cardState(String cardId) {
        return stateOf(scheduleRepository.loadAll(), cardId, System.currentTimeMillis());
    }

    /**
     * Enabled + schedule for many cards, so a page costs one request.
     */
    public synchronized List<CardSchedule> cardStates(List<String> cardIds) {
        Map<String, CardSchedule> schedules = scheduleRepository.loadAll();
        long now = System.currentTimeMillis();
        List<CardSchedule> out = new ArrayList<>(cardIds.size());
        for (String cardId : cardIds) {
            out.add(stateOf(schedules, cardId, now));
        }
        return out;
    }

    /**
     * A synthetic schedule for a card that has no row yet (brand-new shape).
     */
    private CardSchedule newCardSchedule(String cardId) {
        CardSchedule s = new CardSchedule();
        s.setCardId(cardId);
        s.setState("new");
        return s;
    }

    private CardSchedule stateOf(Map<String, CardSchedule> schedules,
                                 String cardId,
                                 long now) {
        CardSchedule schedule = schedules.get(cardId);
        boolean enabled = schedule != null;
        if (!enabled) {
            schedule = newCardSchedule(cardId);
        }
        fillResponseFields(schedule, enabled, now);
        return schedule;
    }

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    /**
     * Enable a card with a fresh schedule, or publish the state change for listeners to handle.
     */
    public synchronized void setEnabled(String cardId,
                                        boolean enabled) {
        boolean wasEnabled = scheduleRepository.getOne(cardId) != null;
        boolean changed = wasEnabled != enabled;
        if (enabled) {
            createIfAbsent(cardId);
        } else if (changed) {
            scheduleRepository.delete(cardId);
        }
        if (changed) {
            mainEventPublisher.publishEvent(new MemoryCycleStateChangedEvent(cardId, enabled));
            log.info("卡片 {} 记忆周期 → {}", cardId, enabled ? "开启" : "关闭");
        }
    }

    /**
     * First grade on a freshly enabled card: a new schedule due immediately.
     */
    private void createIfAbsent(String cardId) {
        if (scheduleRepository.getOne(cardId) != null) {
            return;
        }
        long now = System.currentTimeMillis();
        CardSchedule s = new CardSchedule();
        s.setCardId(cardId);
        s.setState("new");
        s.setDueAt(new Date(now));
        s.setReps(0);
        s.setLapses(0);
        scheduleRepository.save(s);
    }

    /**
     * Grade a card: 1=忘记 2=困难 3=良好 4=简单. Returns the updated state.
     */
    public synchronized CardSchedule review(String cardId,
                                            int grade) {
        if (grade < 1 || grade > 4) {
            throw new IllegalArgumentException("评分必须是 1..4");
        }
        CardSchedule s = scheduleRepository.getOne(cardId);
        if (s == null) {
            throw new IllegalStateException("该卡片未开启记忆周期");
        }
        long now = System.currentTimeMillis();
        apply(s, grade, now);
        s.setReps(s.getReps() + 1);
        s.setLastReviewedAt(new Date(now));
        scheduleRepository.save(s);
        log.info("卡片 {} 评分 {} → 状态 {}，下次复习 {}（level={}）",
                cardId, grade, s.getState(), s.getDueAt(), s.getLevel());
        fillResponseFields(s, true, now);
        return s;
    }

    private void apply(CardSchedule s,
                       int grade,
                       long now) {
        boolean wasInReview = "review".equals(s.getState());

        if (grade == 1) {
            if (wasInReview || "relearning".equals(s.getState())) {
                s.setLapses(s.getLapses() + 1);
            }
            s.setState(wasInReview ? "relearning" : "learning");
            s.setLevel(1);
            s.setDueAt(new Date(now));
            return;
        }

        int currentLevel = Math.max(1, Math.min(s.getLevel(), INTERVAL_DAYS_BY_LEVEL.length));
        int intervalDays = INTERVAL_DAYS_BY_LEVEL[currentLevel - 1][grade - 2];
        graduate(s, now, intervalDays);
        s.setLevel(Math.min(currentLevel + 1, INTERVAL_DAYS_BY_LEVEL.length));
    }

    private void graduate(CardSchedule s,
                          long now,
                          int intervalDays) {
        s.setState("review");
        s.setDueAt(new Date(now + intervalDays * DAY_MS));
    }

    /**
     * 到期卡片：遍历自己的排期文件，取「已开启 + 到点」的那些。
     *
     * <p>返回里只有排期与 cardId —— 卡片属于哪篇笔记、正文是什么，由界面侧自己取（本插件不读笔记数据）。
     */
    public synchronized List<CardSchedule> due(int limit) {
        long now = System.currentTimeMillis();
        int cap = Math.max(1, Math.min(limit, 200));
        List<CardSchedule> rows = new ArrayList<>();
        for (CardSchedule s : scheduleRepository.getAll()) {
            boolean shortPhase = isShortPhase(s.getState());
            if (!shortPhase && s.getDueAt().getTime() > now) {
                continue;
            }
            if (shortPhase) {
                s.setDueAt(new Date(now));
            }
            fillResponseFields(s, true, now);
            rows.add(s);
        }
        rows.sort(Comparator.comparingLong(schedule -> schedule.getDueAt().getTime()));
        return rows.size() > cap ? new ArrayList<>(rows.subList(0, cap)) : rows;
    }

    /**
     * 卡片没了：删掉它的排期条目（由主容器上的 {@code CardChangedEvent} 触发）。
     *
     * @return 是否真的删掉了一条（没排期过返回 false）
     */
    public synchronized boolean delete(String cardId) {
        boolean removed = scheduleRepository.delete(cardId);
        if (removed) {
            log.debug("卡片 {} 的记忆周期排期已删除", cardId);
        }
        return removed;
    }

    private void fillResponseFields(CardSchedule schedule,
                                    boolean enabled,
                                    long now) {
        schedule.setEnabled(enabled);
        schedule.setDue(enabled && (isShortPhase(schedule.getState()) || schedule.getDueAt().getTime() <= now));
        schedule.setGradePreview(previewDue(schedule, now));
    }

    /**
     * 下一次复习时间，按评分 1..4 各预览一份（epoch 毫秒）。
     *
     * <p>实现上直接复用 {@link #apply}——把同一套调度逻辑在副本上跑一遍，预览就永远和真实
     * 评分一致；单独再写一份「间隔公式」迟早会漂移。
     */
    private Map<Integer, Long> previewDue(CardSchedule basis,
                                          long now) {
        Map<Integer, Long> out = new java.util.LinkedHashMap<>();
        for (int g = 1; g <= 4; g++) {
            CardSchedule copy = new CardSchedule();
            copy.setState(basis.getState());
            copy.setLevel(basis.getLevel());
            copy.setLapses(basis.getLapses());
            apply(copy, g, now);
            out.put(g, copy.getDueAt().getTime());
        }
        return out;
    }

}
