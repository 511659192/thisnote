package com.thisnote.ext.baidusync.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.thisnote.ext.baidusync.client.BaiduOAuthClient;
import com.thisnote.ext.baidusync.client.BaiduPanClient;
import com.thisnote.ext.baidusync.client.BaiduPanEntry;
import com.thisnote.ext.baidusync.model.*;
import com.thisnote.ext.baidusync.repository.BaiduSyncSettingsRepository;
import com.thisnote.ext.baidusync.repository.BaiduSyncStateRepository;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.FileAction;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.FileChange;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.LocalItem;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.LocalSnapshot;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.RemoteSnapshot;
import com.thisnote.plugin.api.event.FileChangeEvent;
import com.thisnote.plugin.api.meta.PluginMetadataReader;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class BaiduSyncService {

    private final BaiduSyncSettingsRepository settingsRepository;
    private final BaiduSyncStateRepository stateRepository;
    private final BaiduOAuthClient oauthClient;
    private final BaiduPanClient panClient;
    private final BaiduTransferExecutor transferExecutor;
    private final BaiduSyncPlanner planner;
    private final FileHelper fileHelper;
    private final Path notesRoot;
    private BackupScope backupScope;
    private volatile BaiduSyncStatus status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
    private CompletableFuture<Void> remoteDirectoryInitialization = CompletableFuture.completedFuture(null);
    private String remoteRoot;
    private BaiduSyncSettings settings;

    public BaiduSyncService(BaiduSyncSettingsRepository settingsRepository,
                            BaiduSyncStateRepository stateRepository,
                            BaiduOAuthClient oauthClient,
                            BaiduPanClient panClient,
                            FileHelper fileHelper,
                            @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.settingsRepository = settingsRepository;
        this.stateRepository = stateRepository;
        this.oauthClient = oauthClient;
        this.panClient = panClient;
        this.transferExecutor = new BaiduTransferExecutor(panClient, fileHelper);
        this.planner = new BaiduSyncPlanner(panClient);
        this.fileHelper = fileHelper;
        Path root = Path.of(dataRoot).toAbsolutePath().normalize();
        this.notesRoot = root.resolve("notes");
    }

    @SneakyThrows
    public void initialize(PluginManager pluginManager) {
        Set<String> installedPluginIds = new LinkedHashSet<>();
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            try {
                PluginMetadataReader.read(wrapper);
                installedPluginIds.add(wrapper.getPluginId());
            } catch (Exception e) {
                log.error("插件 {} 的备份清单不可用，已排除其数据目录", wrapper.getPluginId(), e);
            }
        }
        this.backupScope = new BackupScope(notesRoot, installedPluginIds);

        BaiduSyncSettings settings = settingsRepository.getSettings();
        validateSettings(settings);
        this.settings = settings;

        String remoteRoot = "/apps/" + settings.appName() + "/notes";
        this.remoteRoot = remoteRoot;

        initializeRemoteDirectories();
    }

    public void initializeRemoteDirectories() throws IOException {
        BaiduSyncSettings settings = settingsRepository.getSettings();
        if (settings.appName().isBlank() || (settings.accessToken().isBlank() && settings.refreshToken().isBlank())) {
            log.error("百度网盘同步配置不完整：appName 或授权令牌为空");
            return;
        }

        List<String> remoteDirectories = planner.scanLocal(notesRoot, backupScope).directories().stream()
                .sorted(Comparator.comparingInt(directory -> directory.split("/").length))
                .map(directory -> "/apps/" + settings.appName() + "/notes/" + directory)
                .toList();

        if (!remoteDirectoryInitialization.isDone()) {
            return;
        }
        remoteDirectoryInitialization = CompletableFuture.runAsync(() -> {
            String accessToken = oauthClient.accessToken();
            transferExecutor.createRemoteDirectory(accessToken, "/apps", settings.appName() + "/notes");
            remoteDirectories.forEach(directory -> transferExecutor.createRemoteDirectoryPath(accessToken, directory));
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("初始化百度网盘目录失败", error);
            }
        });
    }

    public void requestSyncAfterChange(FileChangeEvent event) {
        Set<Path> changedPaths = Sets.newLinkedHashSet();
        changedPaths.add(event.url());
        Optional.ofNullable(event.previousUrl()).ifPresent(changedPaths::add);
        log.warn("收到文件变更事件：sourcePluginId={}, operation={}, url={}, previousUrl={}", event.sourcePluginId(), event.operation(), event.url(), event.previousUrl());

        status = new BaiduSyncStatus(true, 0, 0, 0, 0, "");
        log.info("百度网盘文件变更同步开始：{}", Joiner.on(",").join(changedPaths));
        syncChangedFiles(changedPaths);
    }

    public BaiduSyncStatus startSync() {
        status = new BaiduSyncStatus(true, 0, 0, 0, 0, "");
        log.info("百度网盘同步开始");
        sync();
        return status;
    }

    public BaiduSyncStatus status() {
        return status;
    }

    /** 状态重置：清空同步基线并复位本次运行的状态（授权凭据保留）。 */
    public void clearSyncState() {
        if (status.running()) {
            throw new IllegalStateException("同步进行中，无法重置同步基线");
        }
        stateRepository.update(BaiduSyncState.empty(notesRoot.toString(), remoteRoot));
        status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
        log.info("百度网盘同步基线已重置（授权凭据保留）");
    }

    public BaiduSyncPreview preview() throws IOException {
        ensureSyncIdle();
        LocalSnapshot localSnapshot = planner.scanLocal(notesRoot, backupScope);
        Map<String, LocalItem> local = localSnapshot.files();

        String accessToken = oauthClient.accessToken();
        RemoteSnapshot remote = planner.scanRemote(accessToken, remoteRoot, backupScope);

        BaiduSyncState baseline = stateRepository.getState(notesRoot.toString(), remoteRoot);
        List<FileChange> changes = planner.plan(local, remote, baseline);
        return planner.preview(local, remote, changes);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledSync() {
        if (settingsRepository.getSettings().autoSync()) {
            startSync();
        }
    }

    private void sync() {
        SyncCounters counters = new SyncCounters();
        counters.onProgress(() -> status = progressStatus(counters, true, ""));
        try {
            String accessToken = oauthClient.accessToken();

            LocalSnapshot localSnapshot = planner.scanLocal(notesRoot, backupScope);
            Map<String, LocalItem> local = localSnapshot.files();
            RemoteSnapshot remote = planner.scanRemote(accessToken, remoteRoot, backupScope);
            BaiduSyncState baseline = stateRepository.getState(notesRoot.toString(), remoteRoot);

            List<FileChange> changes = planner.plan(local, remote, baseline);
            if (CollectionUtil.isEmpty(changes)) {
                status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
                return;
            }
            log.info("change size:{}", CollectionUtil.size(changes));

            counters.conflicts = (int) changes.stream().filter(FileChange::conflict).count();
            counters.initialize(changes);
            transferExecutor.uploadChanges(accessToken, remoteRoot, changes.stream().filter(change -> change.action() == FileAction.UPLOAD).toList(), counters);
            transferExecutor.downloadChanges(accessToken, notesRoot, changes.stream().filter(change -> change.action() == FileAction.DOWNLOAD).toList(), counters);
            transferExecutor.deleteLocalChanges(changes.stream().filter(change -> change.action() == FileAction.DELETE_LOCAL).toList(), counters);
            transferExecutor.deleteRemoteChanges(accessToken, changes.stream().filter(change -> change.action() == FileAction.DELETE_REMOTE).toList(), counters);

            // 传入同步前已知的全部路径而不是仅 changes：双端一致但基线缺失的路径不会进入 changes，
            // 只有覆盖全部路径，逐路径写基线的结果才等价于原先的全量重建。
            Set<String> allPaths = Stream.of(localSnapshot.files().keySet(), remote.files().keySet(), baseline.files().keySet())
                    .flatMap(Set::stream)
                    .filter(backupScope::includes)
                    .collect(Collectors.toSet());
            saveChangedFileBaseline(accessToken, notesRoot, remoteRoot, allPaths);
            status = progressStatus(counters, false, "");
            log.info("百度网盘同步完成：上传 {} 下载 {} 删除 {} 冲突 {}", counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts);
        } catch (Exception e) {
            failSync(counters, e);
        }
    }

    private void failSync(SyncCounters counters,
                          Throwable error) {
        Exception failure = error instanceof Exception exception ? exception : new IllegalStateException("百度网盘同步失败", error);
        String failureMessage = failure.getMessage();
        if (failureMessage == null || failureMessage.isBlank()) {
            failureMessage = "同步失败";
        }
        log.error("百度网盘同步失败：{}", failureMessage, failure);
        status = progressStatus(counters, false, failureMessage);
    }

    private void syncChangedFiles(Set<Path> changedPaths) {
        SyncCounters counters = new SyncCounters();
        counters.onProgress(() -> status = progressStatus(counters, true, ""));
        try {
            Set<String> relativePaths = planner.changedRelativePaths(notesRoot, changedPaths, backupScope);
            if (relativePaths.isEmpty()) {
                status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
                log.debug("relativePaths size:{}", CollectionUtil.size(relativePaths));
                return;
            }

            // 仅按变更路径查询，不扫描整个本地笔记树或网盘目录树。
            Map<String, LocalItem> localFiles = planner.scanLocalPaths(notesRoot, relativePaths, transferExecutor::resolveLocal);

            String accessToken = oauthClient.accessToken();
            Map<String, BaiduPanEntry> remoteFiles = planner.scanRemotePaths(accessToken, remoteRoot, relativePaths);
            List<FileChange> changes = planner.planChanged(relativePaths, localFiles, remoteFiles);
            counters.initialize(changes);

            transferExecutor.uploadChanges(accessToken, remoteRoot, changes.stream().filter(change -> change.action() == FileAction.UPLOAD).toList(), counters);
            transferExecutor.deleteRemoteChanges(accessToken, changes.stream().filter(change -> change.action() == FileAction.DELETE_REMOTE).toList(), counters);
            saveChangedFileBaseline(accessToken, notesRoot, remoteRoot, relativePaths);
            status = progressStatus(counters, false, "");
            log.warn("百度网盘文件变更同步完成：上传 {} 删除 {}（路径{}）", counters.uploaded, counters.deleted, JSON.toJSONString(changedPaths));
        } catch (Exception e) {
            failSync(counters, e);
        }
    }

    private BaiduSyncStatus progressStatus(SyncCounters counters,
                                           boolean running,
                                           String error) {
        String statusError = error == null || error.isBlank()
                ? counters.failureMessage()
                : error;
        return new BaiduSyncStatus(running, counters.uploaded, counters.downloaded,
                counters.deleted, counters.conflicts, statusError, counters.items());
    }

    private void saveChangedFileBaseline(String accessToken,
                                         Path notesRoot,
                                         String remoteRoot,
                                         Set<String> relativePaths) throws IOException {
        Map<String, LocalItem> localFiles = planner.scanLocalPaths(notesRoot, relativePaths, transferExecutor::resolveLocal);
        Map<String, BaiduPanEntry> remoteFiles = planner.scanRemotePaths(accessToken, remoteRoot, relativePaths);
        // 重新读取传输后的两端状态；只有仍匹配的路径才写入基线。
        // 例如上传期间本地文件又被编辑、两端指纹不一致时，移除旧基线，下一轮再按无基线差异决策。
        BaiduSyncState current = stateRepository.getState(notesRoot.toString(), remoteRoot);
        Map<String, String> files = new LinkedHashMap<>();
        current.files().forEach((relative, fingerprint) -> {
            if (backupScope.includes(relative)) {
                files.put(relative, fingerprint);
            }
        });
        Set<String> directories = new LinkedHashSet<>();
        current.directories().stream().filter(backupScope::mayContain).forEach(directories::add);

        for (String relative : relativePaths) {
            LocalItem local = localFiles.get(relative);
            BaiduPanEntry remote = remoteFiles.get(relative);
            FileFingerprint fingerprint = local == null || remote == null ? null : BaiduSyncPlanner.matchingFingerprint(local, remote);
            if (fingerprint != null) {
                files.put(relative, fingerprint.toStorage());
                BaiduSyncPlanner.addParentDirectories(local.parent(), directories);
            } else {
                files.remove(relative);
            }
        }

        stateRepository.update(new BaiduSyncState(notesRoot.toString(), remoteRoot, System.currentTimeMillis(), files, directories));
    }

    private void validateSettings(BaiduSyncSettings settings) {
        if (settings.refreshToken().isBlank() && settings.accessToken().isBlank()) {
            throw new IllegalStateException("请先在插件设置中完成百度网盘授权");
        }
        if (settings.appName().isBlank()) {
            throw new IllegalStateException("请先填写百度应用名称");
        }
    }

    // 同步进行中拒绝预览：不让调用方读到传输到一半的中间状态。
    private void ensureSyncIdle() {
        if (status.running()) {
            throw new IllegalStateException("同步进行中，请稍后重试");
        }
    }

}
