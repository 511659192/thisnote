package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.store.MetaJson;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Append-only op log（风险对策「从第一天记录 ops 操作日志」）。
 *
 * <p>落在 {@code .notes/ops.jsonl}：一行一条 JSON。用追加而不是重写整个文件，是因为日志是唯一
 * 会无限增长的数据，重写它会随使用时间越来越慢；与之配套的是读取只取尾部 N 行。
 * 设备 id 与其它笔记侧设置一起放在 {@code .notes/settings.json}。
 */
@Repository
public class OpsRepository {

    private static final Logger log = LoggerFactory.getLogger(OpsRepository.class);
    private static final String DEVICE_ID_KEY = "device_id";

    private final NoteRepository noteRepository;
    private volatile String deviceId;
    private volatile Integer cachedLines;

    public OpsRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    /** 组装一行操作日志；字段与旧的 ops 表一一对应。 */
    private record OpLine(String id, String deviceId, String entityType, String entityId,
                          String action, String payload, long createdAt) {
    }

    public String deviceId() {
        String cached = deviceId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (deviceId == null) {
                deviceId = loadOrCreateDeviceId();
            }
            return deviceId;
        }
    }

    private String loadOrCreateDeviceId() {
        Map<String, String> settings = settings();
        String existing = settings.get(DEVICE_ID_KEY);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String id = Ids.newId();
        settings.put(DEVICE_ID_KEY, id);
        saveSettings(settings);
        return id;
    }

    public void append(String entityType, String entityId, String action, String payloadJson) {
        OpLine line = new OpLine(Ids.newId(), deviceId(), entityType, entityId, action,
                payloadJson, System.currentTimeMillis());
        noteRepository.appendLine(noteRepository.paths().opsFile(), MetaJson.toJson(line));
        Integer lines = cachedLines;
        cachedLines = lines == null ? null : lines + 1;
    }

    /** 读一个设置项；没有就返回 null。 */
    public String setting(String key) {
        return key == null || key.isBlank() ? null : settings().get(key);
    }

    public void putSetting(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, String> next = settings();
        next.put(key, value == null ? "" : value);
        saveSettings(next);
    }

    /** 最近的 N 条，最新的在前 —— 只读文件尾部，不解析全部历史。 */
    public List<Dtos.OpDto> recent(int limit) {
        int want = Math.max(1, Math.min(limit, 500));
        List<String> lines = readLines();
        List<Dtos.OpDto> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < want; i--) {
            OpLine line = parse(lines.get(i));
            if (line != null) {
                out.add(new Dtos.OpDto(line.id(), line.entityType(), line.entityId(),
                        line.action(), line.payload(), line.createdAt()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private OpLine parse(String json) {
        try {
            return MetaJson.fromJson(json, OpLine.class);
        } catch (IOException | RuntimeException e) {
            log.debug("操作日志行无法解析，已跳过：{}", e.toString());
            return null;
        }
    }

    private List<String> readLines() {
        Path file = noteRepository.paths().opsFile();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            lines.removeIf(String::isBlank);
            return lines;
        } catch (IOException e) {
            log.warn("操作日志无法读取：{}", e.toString());
            return List.of();
        }
    }

    private Map<String, String> settings() {
        Path file = noteRepository.paths().settingsFile();
        try {
            if (!Files.isRegularFile(file)) {
                return new LinkedHashMap<>();
            }
            return new LinkedHashMap<>(MetaJson.stringMap(
                    Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            log.warn("笔记设置无法读取：{}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    private void saveSettings(Map<String, String> settings) {
        noteRepository.writeJson(noteRepository.paths().settingsFile(), Collections.unmodifiableMap(settings));
    }
}
