package com.thisnode.ext.memorycycle.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.memorycycle.model.ScheduleHistoryEntry;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复习历史：{@code notes/plugins/memory-cycle/schedule-history.json}，卡片 id → 记录列表。
 *
 * <p>写入走 {@link FileHelper}，因此每次追加都会发 {@code FileChangeEvent}，百度网盘同步会自动带上这个文件。
 */
@Repository
@Slf4j
public class ScheduleHistoryRepository {

    /** 每张卡片最多保留的记录条数（更早的滚动丢弃）。 */
    public static final int MAX_ENTRIES_PER_CARD = 200;

    private final Path file;
    private final FileHelper fileHelper;

    public ScheduleHistoryRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                                     FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = Paths.get(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("memory-cycle")
                .resolve("schedule-history.json");
    }

    public synchronized List<ScheduleHistoryEntry> get(String cardId) {
        return loadAll().getOrDefault(cardId, List.of());
    }

    public synchronized Map<String, List<ScheduleHistoryEntry>> getFor(List<String> cardIds) {
        Map<String, List<ScheduleHistoryEntry>> all = loadAll();
        Map<String, List<ScheduleHistoryEntry>> out = new LinkedHashMap<>();
        for (String cardId : cardIds) {
            out.put(cardId, all.getOrDefault(cardId, List.of()));
        }
        return out;
    }

    public synchronized void append(ScheduleHistoryEntry entry) {
        Map<String, List<ScheduleHistoryEntry>> all = loadAll();
        List<ScheduleHistoryEntry> entries = new ArrayList<>(all.getOrDefault(entry.getCardId(), List.of()));
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES_PER_CARD) {
            entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES_PER_CARD, entries.size()));
        }
        all.put(entry.getCardId(), entries);
        writeJson(all);
    }

    public synchronized boolean delete(String cardId) {
        Map<String, List<ScheduleHistoryEntry>> all = loadAll();
        if (all.remove(cardId) == null) {
            return false;
        }
        writeJson(all);
        return true;
    }

    /** 状态重置：清空全部历史。 */
    public synchronized void clear() {
        writeJson(new LinkedHashMap<>());
    }

    public synchronized Map<String, List<ScheduleHistoryEntry>> loadAll() {
        try {
            String json;
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return new LinkedHashMap<>();
            }
            Map<String, List<ScheduleHistoryEntry>> history = json.isBlank() ? null : JSON.parseObject(json, new TypeReference<>() {
            });
            return history == null ? new LinkedHashMap<>() : history;
        } catch (IOException | RuntimeException e) {
            log.warn("复习历史文件无法读取，保留原文件并终止加载：{}（{}）", file, e.toString());
            throw new IllegalStateException("复习历史文件无法读取 " + file, e);
        }
    }

    private void writeJson(Map<String, List<ScheduleHistoryEntry>> values) {
        fileHelper.fileWritten(file, values);
    }
}
