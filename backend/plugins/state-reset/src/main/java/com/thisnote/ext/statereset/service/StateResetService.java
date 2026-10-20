package com.thisnote.ext.statereset.service;

import com.thisnote.ext.statereset.StateResetPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.event.PluginStateResetReport;
import com.thisnote.plugin.api.meta.PluginMetadata;
import com.thisnote.plugin.api.meta.PluginMetadataReader;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resets every installed plugin: active plugins run their own cleanup, inactive plugins
 * are cleaned from the static reset manifest without starting their containers.
 */
@Slf4j
@Service
public class StateResetService {

    private static final long ACTIVE_RESET_TIMEOUT_SECONDS = 30L;

    private ApplicationEventPublisher mainEventPublisher;
    private PluginManager pluginManager;
    private final Path pluginDataRoot;

    public StateResetService(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.pluginDataRoot = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins");
    }

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    public void setPluginManager(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager);
    }

    public PluginStateResetReport resetAll() {
        if (pluginManager == null) {
            throw new IllegalStateException("插件管理器尚未就绪");
        }

        List<PluginWrapper> wrappers = List.copyOf(pluginManager.getPlugins());
        Map<String, CompletableFuture<PluginResetResult>> participants = new LinkedHashMap<>();
        for (PluginWrapper wrapper : wrappers) {
            if (wrapper.getPluginState() == PluginState.STARTED) {
                participants.put(wrapper.getPluginId(), new CompletableFuture<>());
            }
        }

        mainEventPublisher.publishEvent(new PluginStateResetEvent(StateResetPlugin.PLUGIN_ID, participants));

        Map<String, PluginResetResult> inactiveResults = new LinkedHashMap<>();
        for (PluginWrapper wrapper : wrappers) {
            String pluginId = wrapper.getPluginId();
            if (!participants.containsKey(pluginId)) {
                inactiveResults.put(pluginId, resetInactive(wrapper));
            }
        }
        Map<String, CompletableFuture<PluginResetResult>> settled = new LinkedHashMap<>();
        participants.forEach((pluginId, future) ->
                settled.put(pluginId, future
                        .orTimeout(ACTIVE_RESET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(error -> {
                            log.error("插件 {} 状态重置未完成", pluginId, error);
                            return PluginResetResult.failed(pluginId, timeoutMessage(error));
                        })));
        settled.values().forEach(future -> future.join());

        List<PluginResetResult> results = new ArrayList<>();
        for (PluginWrapper wrapper : wrappers) {
            String pluginId = wrapper.getPluginId();
            PluginResetResult active = settled.get(pluginId) == null ? null : settled.get(pluginId).join();
            results.add(active == null ? inactiveResults.get(pluginId) : active);
        }
        results.sort(Comparator.comparing(PluginResetResult::pluginId));
        PluginStateResetReport report = new PluginStateResetReport(true, results);
        log.info("插件状态重置完成：ok={}, results={}", report.ok(), report.results().size());
        return report;
    }

    private PluginResetResult resetInactive(PluginWrapper wrapper) {
        String pluginId = wrapper.getPluginId();
        PluginMetadata metadata;
        try {
            metadata = PluginMetadataReader.read(wrapper);
        } catch (Exception e) {
            log.error("插件 {} 静态清单不可用", pluginId, e);
            return PluginResetResult.failed(pluginId, "静态清单不可用：" + safeMessage(e));
        }
        if (metadata.resetTargets().isEmpty()) {
            return PluginResetResult.skipped(pluginId, "没有可重置的持久状态");
        }

        Path pluginDirectory = pluginDataRoot.resolve(pluginId).normalize();
        if (!pluginDirectory.startsWith(pluginDataRoot) || pluginDirectory.equals(pluginDataRoot)) {
            return PluginResetResult.failed(pluginId, "插件 id 对应到非法数据目录");
        }
        try {
            for (String target : metadata.resetTargets()) {
                deleteTarget(pluginDirectory, target);
            }
            return PluginResetResult.reset(pluginId, "停用插件状态文件已清理");
        } catch (Exception e) {
            log.error("停用插件 {} 状态清理失败", pluginId, e);
            return PluginResetResult.failed(pluginId, "停用插件状态清理失败：" + safeMessage(e));
        }
    }

    private void deleteTarget(Path pluginDirectory,
                              String target) throws IOException {
        if (Files.isSymbolicLink(pluginDirectory)) {
            throw new IllegalArgumentException("插件数据目录不能是符号链接：" + pluginDirectory);
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("重置目标不能为空");
        }
        Path relative = Path.of(target);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("重置目标不能是绝对路径：" + target);
        }
        Path resolved = pluginDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(pluginDirectory) || resolved.equals(pluginDirectory)) {
            throw new IllegalArgumentException("重置目标越界：" + target);
        }
        rejectSymbolicLinks(pluginDirectory, resolved);
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("重置目标不能是符号链接：" + target);
        }
        if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new IOException("重置目录包含符号链接：" + file);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir,
                                                          IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return;
        }
        Files.delete(resolved);
    }

    private static void rejectSymbolicLinks(Path base,
                                            Path target) {
        Path current = base;
        for (Path part : base.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("重置路径包含符号链接：" + target);
            }
        }
    }

    private static String timeoutMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "状态重置超时";
        }
        return safeMessage(cause);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }
}
