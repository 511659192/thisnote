package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.client.GraphifyProcessRunner;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Graphify CLI 命令，并负责生成产物的回滚。
 *
 * <p>索引编排和状态由 GraphifyIndexService 负责；本类只把设置转换为
 * CLI 调用，并发布成功产物。
 */
@Slf4j
@RequiredArgsConstructor
final class GraphifyPipelineRunner {

    private final FileHelper fileHelper;
    private final GraphifySettingsService settingsService;
    private final GraphifyProcessRunner processRunner;
    private final Path corpusDir;
    private final Path graphifyOutDir;
    private final Path graphJsonFile;
    private final Path graphHtmlFile;


    void run(GraphifySettingsService.Settings settings,
             String apiKey,
             boolean force,
             boolean labelFirstRun) throws Exception {
        long startedAt = System.nanoTime();
        Map<Path, FileBackup> backups = backupOutputs();
        log.info("Graphify 图谱构建开始：force={}, firstRun={}", force, labelFirstRun);
        try {
            log.info("Graphify extract 开始：corpus={}, backend={}, model={}, force={}", corpusDir, GraphifySettingsService.graphifyBackend(settings.provider()), settings.model(), force);
            List<String> extract = new ArrayList<>(
                    List.of("extract", corpusDir.toAbsolutePath().toString(), "--backend", GraphifySettingsService.graphifyBackend(settings.provider()), "--model", settings.model()));
            if (force) {
                extract.add("--force");
            }
            runCommand(settings, apiKey, extract, "抽取");
            log.info("Graphify extract 完成");

            log.info("Graphify cluster 开始：firstRun={}", labelFirstRun);
            List<String> cluster = new ArrayList<>(List.of("cluster-only", corpusDir.toAbsolutePath().toString()));
            if (labelFirstRun) {
                cluster.addAll(List.of("--backend", GraphifySettingsService.graphifyBackend(settings.provider()), "--model", settings.model()));
            }
            runCommand(settings, apiKey, cluster, "社区聚类");
            log.info("Graphify cluster 完成");
            if (!Files.isRegularFile(graphJsonFile)) {
                throw new IllegalStateException("Graphify 未生成 graph.json");
            }
            log.info("Graphify graph.json 已生成：file={}, size={}", graphJsonFile, Files.size(graphJsonFile));
            publishOutputs();
            log.info("Graphify 图谱构建完成：elapsedMs={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (Exception e) {
            restoreOutputs(backups);
            throw e;
        }
    }

    void relabel(GraphifySettingsService.Settings settings,
                 String apiKey) throws Exception {
        if (!Files.isRegularFile(graphJsonFile)) {
            throw new IllegalStateException("尚无图谱；请先完成首次索引");
        }
        Map<Path, FileBackup> backups = backupOutputs();
        log.info("Graphify 社区命名开始：corpus={}, model={}", corpusDir, settings.model());
        try {
            runCommand(settings, apiKey,
                       List.of("label", corpusDir.toAbsolutePath().toString(), "--backend", GraphifySettingsService.graphifyBackend(settings.provider()), "--model", settings.model()), "社区命名");
            publishOutputs();
            log.info("Graphify 社区命名完成");
        } catch (Exception e) {
            restoreOutputs(backups);
            throw e;
        }
    }

    String requireApiKey(GraphifySettingsService.Settings settings) {
        String apiKey = settingsService.apiKey(settings.aiProfileId());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先在 AI 能力插件中为所选 profile 配置 API key");
        }
        log.debug("Graphify API key 已就绪：profileId={}, keyConfigured=true", settings.aiProfileId());
        return apiKey;
    }

    private void runCommand(GraphifySettingsService.Settings settings,
                            String apiKey,
                            List<String> arguments,
                            String stage) throws IOException {
        GraphifyProcessRunner.Result result;
        log.info("Graphify 命令调用：stage={}, executable={}, arguments={}", stage, executable(settings), arguments);
        try {
            result = processRunner.run(executable(settings), arguments, corpusDir, settings.provider(), apiKey);
        } catch (IOException e) {
            String component = GraphifySettingsService.OPENCODE_GO_PROVIDER.equals(settings.provider()) ? "Graphify 或 OpenCode Go API 转发器" : "Graphify";
            throw new IllegalStateException("无法启动 " + component + "；请确认 Graphify 已安装，或在插件设置中填写可执行文件路径", e);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Graphify " + stage + "失败（exit " + result.exitCode() + "）" + diagnostic(result.output()));
        }
    }

    private Map<Path, FileBackup> backupOutputs() throws IOException {
        Map<Path, FileBackup> backups = new LinkedHashMap<>();
        for (Path path : outputFiles()) {
            if (Files.isRegularFile(path)) {
                backups.put(path, new FileBackup(Files.readAllBytes(path), Files.getLastModifiedTime(path).toMillis()));
            } else {
                backups.put(path, null);
            }
        }
        log.debug("Graphify 输出备份完成：existing={}, total={}", backups.values().stream().filter(java.util.Objects::nonNull).count(), backups.size());
        return backups;
    }

    private void restoreOutputs(Map<Path, FileBackup> backups) {
        for (Map.Entry<Path, FileBackup> entry : backups.entrySet()) {
            Path path = entry.getKey();
            FileBackup backup = entry.getValue();
            if (backup == null) {
                if (!fileHelper.fileDeleted(path) && Files.exists(path)) {
                    log.error("删除 Graphify 文件失败：{}", path);
                }
            } else {
                fileHelper.fileWritten(path, new ByteArrayInputStream(backup.bytes()), backup.modifiedAt());
                log.warn("Graphify 回滚输出文件：{}", path);
            }
        }
    }

    private void publishOutputs() throws IOException {
        int published = 0;
        for (Path path : outputFiles()) {
            if (Files.isRegularFile(path)) {
                byte[] bytes = Files.readAllBytes(path);
                FileTime modifiedAt = Files.getLastModifiedTime(path);
                fileHelper.fileWritten(path, new ByteArrayInputStream(bytes), modifiedAt.toMillis());
                published++;
                log.debug("Graphify 输出已发布：file={}, bytes={}", path, bytes.length);
            }
        }
        log.info("Graphify 图谱输出已发布：files={}", published);
    }

    private List<Path> outputFiles() {
        return List.of(graphJsonFile, graphHtmlFile, graphifyOutDir.resolve("GRAPH_REPORT.md"), graphifyOutDir.resolve(".graphify_labels.json"), graphifyOutDir.resolve(".graphify_analysis.json"));
    }

    private static String executable(GraphifySettingsService.Settings settings) {
        return settings.executablePath() == null || settings.executablePath().isBlank() ? "graphify" : settings.executablePath();
    }

    private static String diagnostic(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String important = output.lines().filter(line -> {
            String lower = line.toLowerCase(Locale.ROOT);
            return lower.contains("error") || lower.contains("failed") || lower.contains("incomplete") || lower.contains("shrink") || lower.contains("semantic cache");
        }).distinct().reduce((left, right) -> left + "\n" + right).orElse("");
        if (!important.isBlank()) {
            output = important;
        }
        String trimmed = output.trim();
        if (trimmed.length() > 1800) {
            trimmed = trimmed.substring(trimmed.length() - 1800);
        }
        return "：" + trimmed;
    }

    private record FileBackup(byte[] bytes, long modifiedAt) {
    }
}
