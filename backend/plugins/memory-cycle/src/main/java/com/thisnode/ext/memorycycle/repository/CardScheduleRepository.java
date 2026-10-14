package com.thisnode.ext.memorycycle.repository;

import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.memorycycle.app.CardSchedule;
import com.thisnode.plugin.api.util.JsonFileUtils;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CardScheduleRepository {

    private static final Logger log = LoggerFactory.getLogger(CardScheduleRepository.class);

    private final Path file;

    public CardScheduleRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.file = PluginDataPathUtils.pluginDataDirectory(Paths.get(dataRoot), "memory-cycle")
                                             .resolve("scheduling.json");
    }

    public synchronized CardSchedule getOne(String cardId) {
        return loadAll().get(cardId);
    }

    public synchronized List<CardSchedule> getAll() {
        return new ArrayList<>(loadAll().values());
    }

    public synchronized void save(CardSchedule schedule) {
        Map<String, CardSchedule> schedules = loadAll();
        schedules.put(schedule.getCardId(), schedule);
        writeJson(schedules);
    }

    public synchronized boolean delete(String cardId) {
        Map<String, CardSchedule> schedules = loadAll();
        if (schedules.remove(cardId) == null) {
            return false;
        }
        writeJson(schedules);
        return true;
    }

    private Map<String, CardSchedule> loadAll() {
        try {
            Map<String, CardSchedule> schedules = JsonFileUtils.read(file, new TypeReference<>() {
            });
            if (schedules == null) {
                return new LinkedHashMap<>();
            }
            for (Map.Entry<String, CardSchedule> entry : schedules.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || !entry.getKey().equals(entry.getValue().getCardId())) {
                    throw new IllegalArgumentException("排期 JSON 中的 cardId 与对象字段不匹配");
                }
            }
            return schedules;
        } catch (IOException | RuntimeException e) {
            log.warn("排期文件无法读取，保留原文件并终止加载：{}（{}）", file, e.toString());
            throw new IllegalStateException("排期文件无法读取 " + file, e);
        }
    }

    private void writeJson(Map<String, CardSchedule> values) {
        JsonFileUtils.write(file, values);
    }
}
