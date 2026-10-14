package com.thisnode.ext.memorycycle.app;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.thisnode.ext.memorycycle.api.dto.ScheduleView;
import com.thisnode.plugin.api.MemoryCycleStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final DateTimeFormatter STORED_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu年MM月dd日 HH:mm:ss");

    /**
     * 排期存放在本插件自己的 {@code scheduling.json}，有排期即表示卡片已开启。
     *
     * <p>本插件不读笔记内容，也不依赖核心插件；卡片被删除时由主容器发出的变更事件清理排期。
     */
    private final Path file;
    private Map<String, CardSchedule> schedules;
    private ApplicationEventPublisher mainEventPublisher;

    public SrsService(ApplicationEventPublisher mainEventPublisher,
                      @org.springframework.beans.factory.annotation.Value(
                              "${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.mainEventPublisher = mainEventPublisher;
        this.file = Paths.get(dataRoot, "plugins", "memory-cycle", "scheduling.json");
    }

    private static boolean isShortPhase(String state) {
        return "new".equals(state) || "learning".equals(state) || "relearning".equals(state);
    }

    /** Enabled + schedule for one card. Schedule is {@code null} until the first review. */
    public synchronized Map<String, Object> cardState(String cardId) {
        CardSchedule s = load(cardId);
        CardSchedule basis = s == null ? newCardSchedule(cardId) : s;
        return Map.of(
                "cardId", cardId,
                "enabled", s != null,
                "schedule", s == null ? emptySchedule(cardId) : scheduleView(s),
                "gradePreview", previewDue(basis, System.currentTimeMillis()));
    }

    /** Enabled + schedule for many cards, so a page costs one request. */
    public synchronized List<Map<String, Object>> cardStates(List<String> cardIds) {
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
        return s;
    }

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    /** Enable a card with a fresh schedule, or publish the state change for listeners to handle. */
    public synchronized void setEnabled(String cardId, boolean enabled) {
        boolean wasEnabled = load(cardId) != null;
        boolean changed = wasEnabled != enabled;
        ApplicationEventPublisher publisher = mainEventPublisher;
        if (changed && publisher == null) {
            throw new IllegalStateException("主容器事件发布器尚未就绪");
        }

        if (enabled) {
            if (createIfAbsent(cardId)) {
                saveAll();
            }
        } else if (changed) {
            schedules().remove(cardId);
            saveAll();
        }
        if (changed) {
            publisher.publishEvent(new MemoryCycleStateChangedEvent(cardId, enabled));
        }
    }

    /** First grade on a freshly enabled card: a new schedule due immediately. */
    private boolean createIfAbsent(String cardId) {
        if (load(cardId) != null) {
            return false;
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
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        schedules().put(cardId, s);
        return true;
    }

    /** Grade a card: 1=忘记 2=困难 3=良好 4=简单. Returns the updated state. */
    public synchronized ScheduleView review(String cardId, int grade) {
        if (grade < 1 || grade > 4) {
            throw new IllegalArgumentException("评分必须是 1..4");
        }
        CardSchedule s = load(cardId);
        if (s == null) {
            throw new IllegalStateException("该卡片未开启记忆周期");
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
    public synchronized List<Map<String, Object>> due(int limit) {
        long now = System.currentTimeMillis();
        int cap = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CardSchedule s : schedules().values()) {
            boolean shortPhase = isShortPhase(s.getState());
            if (!shortPhase && s.getDueAt() > now) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardId", s.getCardId());
            row.put("dueAt", shortPhase ? now : s.getDueAt());
            row.put("state", s.getState());
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

    public synchronized boolean forgetDeletedCard(String cardId) {
        return forget(cardId);
    }

    /** 读取并按需迁移排期文件。 */
    private Map<String, CardSchedule> loadAll() {
        Map<String, CardSchedule> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                writeJson(out);
                return out;
            }

            String trimmed = content.stripLeading();
            if (trimmed.startsWith("{")) {
                Map<String, Object> loaded = JSON.parseObject(content);
                if (loaded == null) {
                    throw new IllegalArgumentException("排期 JSON 根节点必须是对象");
                }
                boolean needsTimestampMigration = false;
                boolean needsLearningStepRemoval = false;
                for (Map.Entry<String, Object> entry : loaded.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> row)
                            || entry.getKey() == null
                            || !entry.getKey().equals(String.valueOf(row.get("cardId")))) {
                        throw new IllegalArgumentException("排期 JSON 中的 cardId 与对象字段不匹配");
                    }
                    needsTimestampMigration |= hasNumericTimestamp(row);
                    needsLearningStepRemoval |= row.containsKey("learningStep");
                    out.put(entry.getKey(), scheduleFromJson(entry.getKey(), row));
                }
                if (needsTimestampMigration || needsLearningStepRemoval) {
                    Path backup = backupJsonFile();
                    writeJson(out);
                    log.info("排期 JSON 已更新并备份原文件：{}", backup);
                }
                return out;
            }
            if (!looksLikeLegacyProperties(trimmed)) {
                throw new IllegalArgumentException("排期文件格式无法识别");
            }

            out = loadLegacyProperties();
            Path backup = backupLegacyFile();
            writeJson(out);
            log.info("旧排期文件已迁移为 JSON，备份位于 {}", backup);
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("排期文件无法读取，保留原文件并终止加载：{}（{}）", file, e.toString());
            throw new IllegalStateException("排期文件无法读取 " + file, e);
        }
    }

    private Map<String, CardSchedule> loadLegacyProperties() throws IOException {
        Map<String, CardSchedule> out = new LinkedHashMap<>();
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(file, StandardOpenOption.READ)) {
            props.load(in);
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

    private static boolean looksLikeLegacyProperties(String content) {
        if (content.startsWith("#") || content.startsWith("!")) {
            return true;
        }
        String firstLine = content.lines().findFirst().orElse("").stripLeading();
        int delimiter = firstLine.indexOf('=');
        if (delimiter < 0) {
            delimiter = firstLine.indexOf(':');
        }
        return delimiter > 0 && firstLine.substring(0, delimiter).contains("|");
    }

    private Path backupLegacyFile() throws IOException {
        String baseName = file.getFileName() + ".properties";
        Path backup = file.resolveSibling(baseName + ".bak");
        for (int suffix = 1; Files.exists(backup); suffix++) {
            backup = file.resolveSibling(baseName + "-" + suffix + ".bak");
        }
        return Files.copy(file, backup);
    }

    private Path backupJsonFile() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        for (int suffix = 1; Files.exists(backup); suffix++) {
            backup = file.resolveSibling(file.getFileName() + "-" + suffix + ".bak");
        }
        return Files.copy(file, backup);
    }

    private void saveAll() {
        writeJson(schedules);
    }

    private void writeJson(Map<String, CardSchedule> values) {
        Path tempFile = null;
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> jsonValues = new LinkedHashMap<>();
            for (Map.Entry<String, CardSchedule> entry : values.entrySet()) {
                jsonValues.put(entry.getKey(), scheduleToJson(entry.getValue()));
            }
            String json = JSON.toJSONString(jsonValues, JSONWriter.Feature.PrettyFormat);
            tempFile = Files.createTempFile(file.getParent(), "scheduling.json.", ".tmp");
            Files.writeString(tempFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("排期写入失败 " + file, e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("无法清理排期临时文件：{}（{}）", tempFile, e.toString());
                }
            }
        }
    }

    private static void applyProperty(CardSchedule s, String key, String value) {
        switch (key) {
            case "state" -> s.setState(value);
            case "ease" -> s.setEase(parseNumber(value, EASE_INIT));
            case "intervalDays" -> s.setIntervalDays(parseNumber(value, 0));
            case "dueAt" -> s.setDueAt(parseLegacyTimestamp(value, 0));
            case "lastReviewedAt" -> s.setLastReviewedAt(value == null || value.isBlank()
                    ? null : parseLegacyTimestamp(value, 0));
            case "reps" -> s.setReps((int) parseNumber(value, 0));
            case "lapses" -> s.setLapses((int) parseNumber(value, 0));
            case "createdAt" -> s.setCreatedAt(parseLegacyTimestamp(value, 0));
            case "updatedAt" -> s.setUpdatedAt(parseLegacyTimestamp(value, 0));
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

    private static long parseLegacyTimestamp(String raw, long fallback) {
        return normalizeTimestamp((long) parseNumber(raw, fallback));
    }

    private static String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static double number(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean hasNumericTimestamp(Map<?, ?> row) {
        return row.get("dueAt") instanceof Number
                || row.get("lastReviewedAt") instanceof Number
                || row.get("createdAt") instanceof Number
                || row.get("updatedAt") instanceof Number;
    }

    private static CardSchedule scheduleFromJson(String cardId, Map<?, ?> row) {
        CardSchedule schedule = new CardSchedule();
        schedule.setCardId(cardId);
        schedule.setState(text(row.get("state"), "new"));
        schedule.setEase(number(row.get("ease"), EASE_INIT));
        schedule.setIntervalDays(number(row.get("intervalDays"), 0));
        schedule.setDueAt(parseTimestamp(row.get("dueAt"), 0L));
        schedule.setLastReviewedAt(parseTimestamp(row.get("lastReviewedAt"), null));
        schedule.setReps((int) number(row.get("reps"), 0));
        schedule.setLapses((int) number(row.get("lapses"), 0));
        schedule.setCreatedAt(parseTimestamp(row.get("createdAt"), 0L));
        schedule.setUpdatedAt(parseTimestamp(row.get("updatedAt"), 0L));
        return schedule;
    }

    private static Map<String, Object> scheduleToJson(CardSchedule schedule) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cardId", schedule.getCardId());
        row.put("state", schedule.getState());
        row.put("ease", schedule.getEase());
        row.put("intervalDays", schedule.getIntervalDays());
        row.put("dueAt", formatTimestamp(schedule.getDueAt()));
        row.put("lastReviewedAt", schedule.getLastReviewedAt() == null
                ? null : formatTimestamp(schedule.getLastReviewedAt()));
        row.put("reps", schedule.getReps());
        row.put("lapses", schedule.getLapses());
        row.put("createdAt", formatTimestamp(schedule.getCreatedAt()));
        row.put("updatedAt", formatTimestamp(schedule.getUpdatedAt()));
        return row;
    }

    private static String formatTimestamp(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(STORED_TIMESTAMP_FORMAT);
    }

    private static Long parseTimestamp(Object raw, Long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return normalizeTimestamp(number.longValue());
        }
        if (raw instanceof String value) {
            if (value.isBlank()) {
                return fallback;
            }
            try {
                return LocalDateTime.parse(value, STORED_TIMESTAMP_FORMAT)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("排期时间格式必须是 yyyy年MM月dd日 HH:mm:ss", e);
            }
        }
        throw new IllegalArgumentException("排期时间必须是毫秒数字或 yyyy年MM月dd日 HH:mm:ss 字符串");
    }

    private static long normalizeTimestamp(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .withNano(0)
                .toInstant()
                .toEpochMilli();
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
            copy.setLapses(basis.getLapses());
            apply(copy, g, now);
            out.put(g, copy.getDueAt());
        }
        return out;
    }

}
