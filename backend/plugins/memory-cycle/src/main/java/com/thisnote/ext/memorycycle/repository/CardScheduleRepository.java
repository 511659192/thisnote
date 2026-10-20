package com.thisnote.ext.memorycycle.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnote.ext.memorycycle.model.CardSchedule;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CardScheduleRepository {

    private final Path file;
    private final FileHelper fileHelper;

    public CardScheduleRepository(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot,
                                  FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = Paths.get(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("memory-cycle")
                .resolve(".scheduling.json");
    }

    public synchronized CardSchedule getOne(String cardId) {
        return getAllById().get(cardId);
    }

    public synchronized List<CardSchedule> getAll() {
        return new ArrayList<>(getAllById().values());
    }

    public synchronized void update(CardSchedule schedule) {
        Map<String, CardSchedule> schedules = getAllById();
        schedules.put(schedule.getCardId(), schedule);
        writeJson(schedules);
    }

    public synchronized boolean delete(String cardId) {
        Map<String, CardSchedule> schedules = getAllById();
        if (schedules.remove(cardId) == null) {
            return false;
        }
        writeJson(schedules);
        return true;
    }

    /** 状态重置：把排期文件重置为空 map。 */
    public synchronized void deleteAll() {
        writeJson(new LinkedHashMap<>());
    }

    public synchronized Map<String, CardSchedule> getAllById() {
        try {
            String json;
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return new LinkedHashMap<>();
            }
            Map<String, CardSchedule> schedules = json.isBlank() ? null : JSON.parseObject(json, new TypeReference<>() {
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
        } catch (Exception e) {
            throw new IllegalStateException("排期文件无法读取 " + file, e);
        }
    }

    private void writeJson(Map<String, CardSchedule> values) {
        fileHelper.fileWritten(file, values);
    }
}
