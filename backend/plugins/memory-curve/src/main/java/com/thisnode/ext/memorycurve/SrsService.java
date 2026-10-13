package com.thisnode.ext.memorycurve;

import com.thisnode.ext.memorycurve.api.dto.ScheduleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@org.springframework.stereotype.Service
public class SrsService {

    private static final double EASE_INIT = 2.5;
    private static final double EASE_MIN = 1.3;
    private static final double EASE_MAX = 3.0;
    private static final double EASE_DOWN_AGAIN = 0.20;
    private static final double EASE_DOWN_HARD = 0.15;
    private static final double EASE_UP_EASY = 0.15;

    private static final int HARD_INTERVAL_DAYS = 1;
    private static final int GOOD_INTERVAL_DAYS = 2;
    private static final int EASY_INTERVAL_DAYS = 3;

    private static final long DAY_MS = 86_400_000L;

    private static final Logger log = LoggerFactory.getLogger(SrsService.class);

    /**
     * 排期存放在本插件自己的 {@code scheduling.meta}，卡片开关存放在 {@code enabled-cards.properties}。
     *
     * <p>本插件因此是「纯状态管理器」：只管排期与开关，**不读笔记内容**，也不依赖核心插件 ——
     * 核心插件缺位时它照样能独立启动。代价是卡片被删后条目会残留（没有通知机制能感知），
     * 所以 due 列表把它们原样返回，由界面侧跳过（卡片内容由界面侧自己取）。
     */
    private final EnabledCardsStore enabledCards;
    private final Path file;
    private Map<String, CardSchedule> schedules;

    public SrsService(EnabledCardsStore enabledCards,
                      @org.springframework.beans.factory.annotation.Value(
                              "${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.enabledCards = enabledCards;
        this.file = Paths.get(dataRoot, "plugins", "memory-curve", "scheduling.meta");
    }

    private boolean enabled(String cardId) {
        return enabledCards.isEnabled(cardId);
    }

    private static boolean isShortPhase(String state) {
        return "new".equals(state) || "learning".equals(state) || "relearning".equals(state);
    }

    /** Enabled + schedule for one card. Schedule is {@code null} until the first review. */
    public Map<String, Object> cardState(String cardId) {
        CardSchedule s = load(cardId);
        CardSchedule basis = s == null ? newCardSchedule(cardId) : s;
        return Map.of(
                "cardId", cardId,
                "enabled", enabled(cardId),
                "schedule", s == null ? emptySchedule(cardId) : scheduleView(s),
                "learningStep", basis.getLearningStep(),
                "gradePreview", previewDue(basis, System.currentTimeMillis()));
    }

    /** Enabled + schedule for many cards, so a page costs one request. */
    public List<Map<String, Object>> cardStates(List<String> cardIds) {
        List<Map<String, Object>> out = new ArrayList<>(cardIds.size());
        for (String cardId : cardIds) {
            out.add(cardState(cardId));
        }
        return out;
    }

    /** A synthetic schedule for a card that has no row yet (brand-new shape). */
    private CardSchedule newCardSchedule(String cardId) {
        CardSchedule s = new CardSchedule();
        s.setCardId(cardId);
        s.setState("new");
        s.setEase(EASE_INIT);
        s.setLearningStep(0);
        return s;
    }

    /** Enable or disable a card; disabling preserves its schedule history. */
    public void setEnabled(String cardId, boolean enabled) {
        enabledCards.setEnabled(cardId, enabled);
        if (enabled) {
            createIfAbsent(cardId);
        }
    }

    /** First grade on a freshly enabled card: a new schedule due immediately. */
    private void createIfAbsent(String cardId) {
        if (load(cardId) != null) {
            return;
        }
        long now = System.currentTimeMillis();
        CardSchedule s = new CardSchedule();
        s.setCardId(cardId);
        s.setState("new");
        s.setEase(EASE_INIT);
        s.setIntervalDays(0);
        s.setDueAt(now);
        s.setReps(0);
        s.setLapses(0);
        s.setLearningStep(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        save(s);
    }

    /** Grade a card: 1=忘记 2=困难 3=良好 4=简单. Returns the updated state. */
    public ScheduleView review(String cardId, int grade) {
        if (grade < 1 || grade > 4) {
            throw new IllegalArgumentException("评分必须是 1..4");
        }
        if (!enabled(cardId)) {
            throw new IllegalStateException("该卡片未开启记忆曲线");
        }
        CardSchedule s = load(cardId);
        if (s == null) {
            // 排期丢失（meta 被手工改坏、卡片是导入进来的）：当作全新卡片，而不是报错。
            createIfAbsent(cardId);
            s = load(cardId);
        }
        long now = System.currentTimeMillis();
        apply(s, grade, now);
        s.setReps(s.getReps() + 1);
        s.setLastReviewedAt(now);
        s.setUpdatedAt(now);
        save(s);
        return scheduleView(s);
    }

    private void apply(CardSchedule s, int grade, long now) {
        boolean hadGraduated = "review".equals(s.getState());

        if (grade == 1) {
            // Forgot: repeat in the review queue and punish ease.
            s.setEase(clampEase(s.getEase() - EASE_DOWN_AGAIN));
            if (hadGraduated) {
                s.setLapses(s.getLapses() + 1);
            }
            s.setState(hadGraduated ? "relearning" : "learning");
            s.setLearningStep(0);
            s.setDueAt(now);
            return;
        }

        switch (grade) {
            case 2 -> {
                if (hadGraduated) {
                    s.setEase(clampEase(s.getEase() - EASE_DOWN_HARD));
                }
            }
            case 4 -> s.setEase(clampEase(s.getEase() + EASE_UP_EASY));
            default -> {
            }
        }

        int intervalDays = switch (grade) {
            case 2 -> HARD_INTERVAL_DAYS;
            case 3 -> GOOD_INTERVAL_DAYS;
            case 4 -> EASY_INTERVAL_DAYS;
            default -> throw new IllegalArgumentException("评分必须是 1..4");
        };
        graduate(s, now, intervalDays);
    }

    private void graduate(CardSchedule s, long now, int intervalDays) {
        s.setState("review");
        s.setLearningStep(0);
        s.setIntervalDays(intervalDays);
        s.setDueAt(now + intervalDays * DAY_MS);
    }

    private double clampEase(double ease) {
        return Math.min(EASE_MAX, Math.max(EASE_MIN, ease));
    }

    /**
     * 到期卡片：遍历自己的排期文件，取「已开启 + 到点」的那些。
     *
     * <p>返回里只有排期与 cardId —— 卡片属于哪篇笔记、正文是什么，由界面侧自己取（本插件不读笔记数据）。
     */
    public List<Map<String, Object>> due(int limit) {
        long now = System.currentTimeMillis();
        int cap = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CardSchedule s : schedules().values()) {
            boolean shortPhase = isShortPhase(s.getState());
            if (!enabled(s.getCardId()) || (!shortPhase && s.getDueAt() > now)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardId", s.getCardId());
            row.put("dueAt", shortPhase ? now : s.getDueAt());
            row.put("state", s.getState());
            row.put("learningStep", s.getLearningStep());
            row.put("gradePreview", previewDue(s, now));
            rows.add(row);
        }
        rows.sort(Comparator.<Map<String, Object>>comparingLong(
                row -> ((Number) row.get("dueAt")).longValue()));
        return rows.size() > cap ? new ArrayList<>(rows.subList(0, cap)) : rows;
    }

    // -------------------------------------------------- 排期读写（自己的排期文件）

    private Map<String, CardSchedule> schedules() {
        if (schedules != null) {
            return schedules;
        }
        synchronized (this) {
            if (schedules == null) {
                schedules = loadAll();
            }
            return schedules;
        }
    }

    /** 读一张卡片的排期；没有排期返回 {@code null}。 */
    private CardSchedule load(String cardId) {
        return schedules().get(cardId);
    }

    private void save(CardSchedule s) {
        schedules().put(s.getCardId(), s);
        saveAll();
    }

    /**
     * 卡片没了：删掉它的排期条目（由主容器上的 {@code CardChangedEvent} 触发）。
     *
     * @return 是否真的删掉了一条（没排期过返回 false）
     */
    public synchronized boolean forget(String cardId) {
        if (schedules().remove(cardId) == null) {
            return false;
        }
        saveAll();
        return true;
    }

    /** 把排期条目落成 {@code cardId|key = value} 的 Properties 行。 */
    private Map<String, CardSchedule> loadAll() {
        Map<String, CardSchedule> out = new LinkedHashMap<>();
        if (!java.nio.file.Files.isRegularFile(file)) {
            return out;
        }
        java.util.Properties props = new java.util.Properties();
        try (var in = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            log.warn("排期文件无法读取，按空库处理：{}（{}）", file, e.toString());
            return out;
        }
        for (String name : props.stringPropertyNames()) {
            int sep = name.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            String cardId = name.substring(0, sep);
            String key = name.substring(sep + 1);
            CardSchedule s = out.computeIfAbsent(cardId, k -> {
                CardSchedule fresh = new CardSchedule();
                fresh.setCardId(cardId);
                return fresh;
            });
            applyProperty(s, key, props.getProperty(name));
        }
        return out;
    }

    private void saveAll() {
        java.util.Properties props = new java.util.Properties();
        for (CardSchedule s : schedules.values()) {
            for (Map.Entry<String, String> kv : scheduleFields(s).entrySet()) {
                props.setProperty(s.getCardId() + "|" + kv.getKey(), kv.getValue());
            }
        }
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (var out = java.nio.file.Files.newOutputStream(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                props.store(out, "memory-curve schedules: cardId|key = value");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("排期写入失败 " + file, e);
        }
    }

    /** 排期条目的键值投影（键名与旧表列名一一对应，便于人工核对）。 */
    private static Map<String, String> scheduleFields(CardSchedule s) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("state", s.getState());
        out.put("ease", String.valueOf(s.getEase()));
        out.put("intervalDays", String.valueOf(s.getIntervalDays()));
        out.put("dueAt", String.valueOf(s.getDueAt()));
        out.put("lastReviewedAt", s.getLastReviewedAt() == null ? "" : String.valueOf(s.getLastReviewedAt()));
        out.put("reps", String.valueOf(s.getReps()));
        out.put("lapses", String.valueOf(s.getLapses()));
        out.put("learningStep", String.valueOf(s.getLearningStep()));
        out.put("createdAt", String.valueOf(s.getCreatedAt()));
        out.put("updatedAt", String.valueOf(s.getUpdatedAt()));
        return out;
    }

    private static void applyProperty(CardSchedule s, String key, String value) {
        switch (key) {
            case "state" -> s.setState(value);
            case "ease" -> s.setEase(parseNumber(value, EASE_INIT));
            case "intervalDays" -> s.setIntervalDays(parseNumber(value, 0));
            case "dueAt" -> s.setDueAt((long) parseNumber(value, 0));
            case "lastReviewedAt" -> s.setLastReviewedAt(value == null || value.isBlank()
                    ? null : (long) parseNumber(value, 0));
            case "reps" -> s.setReps((int) parseNumber(value, 0));
            case "lapses" -> s.setLapses((int) parseNumber(value, 0));
            case "learningStep" -> s.setLearningStep((int) parseNumber(value, 0));
            case "createdAt" -> s.setCreatedAt((long) parseNumber(value, 0));
            case "updatedAt" -> s.setUpdatedAt((long) parseNumber(value, 0));
            default -> {
            }
        }
    }

    private static double parseNumber(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static double number(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    private ScheduleView emptySchedule(String cardId) {
        return new ScheduleView(cardId, "new", EASE_INIT, 0, 0, null, 0, 0, false);
    }

    private ScheduleView scheduleView(CardSchedule schedule) {
        long now = System.currentTimeMillis();
        long dueAt = isShortPhase(schedule.getState()) ? now : schedule.getDueAt();
        return new ScheduleView(schedule.getCardId(), schedule.getState(), schedule.getEase(), schedule.getIntervalDays(),
                dueAt, schedule.getLastReviewedAt(), schedule.getReps(), schedule.getLapses(), dueAt <= now);
    }

    /**
     * 下一次复习时间，按评分 1..4 各预览一份（epoch 毫秒）。
     *
     * <p>实现上直接复用 {@link #apply}——把同一套调度逻辑在副本上跑一遍，预览就永远和真实
     * 评分一致；单独再写一份「间隔公式」迟早会漂移。
     */
    private Map<Integer, Long> previewDue(CardSchedule basis, long now) {
        Map<Integer, Long> out = new java.util.LinkedHashMap<>();
        for (int g = 1; g <= 4; g++) {
            CardSchedule copy = new CardSchedule();
            copy.setState(basis.getState());
            copy.setEase(basis.getEase());
            copy.setIntervalDays(basis.getIntervalDays());
            copy.setLearningStep(basis.getLearningStep());
            copy.setLapses(basis.getLapses());
            apply(copy, g, now);
            out.put(g, copy.getDueAt());
        }
        return out;
    }

}
