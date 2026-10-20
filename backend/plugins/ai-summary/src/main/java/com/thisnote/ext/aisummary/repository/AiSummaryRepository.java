package com.thisnote.ext.aisummary.repository;

import com.alibaba.fastjson2.TypeReference;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Repository
public class AiSummaryRepository {

    private final FileHelper fileHelper;
    private final Path summariesFile;

    public AiSummaryRepository(FileHelper fileHelper,
                               @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins").resolve("ai-summary");
        this.summariesFile = pluginDir.resolve("summaries.json");
    }

    public synchronized StoredSummary getOne(String noteId) {
        return read().get(noteId);
    }

    public synchronized void update(String noteId,
                                    String summary,
                                    String generatedAt) {
        Map<String, StoredSummary> summaries = read();
        summaries.put(noteId, new StoredSummary(summary, generatedAt, false));
        write(summaries);
    }

    public synchronized boolean updateStale(String noteId) {
        Map<String, StoredSummary> summaries = read();
        StoredSummary current = summaries.get(noteId);
        if (current == null || current.stale()) {
            return false;
        }
        summaries.put(noteId, new StoredSummary(current.summary(), current.generatedAt(), true));
        write(summaries);
        return true;
    }

    public synchronized int updateAllStale() {
        Map<String, StoredSummary> summaries = read();
        int changed = 0;
        for (Map.Entry<String, StoredSummary> entry : summaries.entrySet()) {
            StoredSummary current = entry.getValue();
            if (current.stale()) {
                continue;
            }
            entry.setValue(new StoredSummary(current.summary(), current.generatedAt(), true));
            changed++;
        }
        if (changed > 0) {
            write(summaries);
        }
        return changed;
    }

    public synchronized boolean deleteOne(String noteId) {
        Map<String, StoredSummary> summaries = read();
        if (summaries.remove(noteId) == null) {
            return false;
        }
        write(summaries);
        return true;
    }

    public synchronized void deleteAll() {
        if (!Files.exists(summariesFile)) {
            return;
        }
        if (!fileHelper.fileDeleted(summariesFile) && Files.exists(summariesFile)) {
            log.error("删除 AI 总结文件失败：{}", summariesFile);
            throw new IllegalStateException("删除 AI 总结文件失败：" + summariesFile);
        }
    }

    private Map<String, StoredSummary> read() {
        try {
            return FileHelper.readMapOrEmpty(
                    summariesFile,
                    new TypeReference<LinkedHashMap<String, StoredSummary>>() {
                    },
                    "AI 总结"
            );
        } catch (RuntimeException e) {
            log.error("AI 总结索引读取失败：{}", summariesFile, e);
            throw e;
        }
    }

    private void write(Map<String, StoredSummary> summaries) {
        if (summaries.isEmpty()) {
            if (Files.exists(summariesFile) && !fileHelper.fileDeleted(summariesFile) && Files.exists(summariesFile)) {
                log.error("删除空的 AI 总结文件失败：{}", summariesFile);
                throw new IllegalStateException("删除 AI 总结文件失败：" + summariesFile);
            }
            return;
        }
        try {
            fileHelper.fileWritten(summariesFile, summaries);
            log.info("AI 总结索引已写入：path={}, notes={}", summariesFile, summaries.size());
        } catch (RuntimeException e) {
            log.error("AI 总结索引写入失败：path={}, notes={}", summariesFile, summaries.size(), e);
            throw e;
        }
    }

    public record StoredSummary(String summary,
                                String generatedAt,
                                boolean stale) {
    }
}
