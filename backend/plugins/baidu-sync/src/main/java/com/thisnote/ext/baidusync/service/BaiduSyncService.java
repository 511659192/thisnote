package com.thisnote.ext.baidusync.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.thisnote.ext.baidusync.client.BaiduOAuthClient;
import com.thisnote.ext.baidusync.client.BaiduPanClient;
import com.thisnote.ext.baidusync.client.BaiduPanEntry;
import com.thisnote.ext.baidusync.client.BaiduPanException;
import com.thisnote.ext.baidusync.model.*;
import com.thisnote.ext.baidusync.repository.BaiduSyncSettingsRepository;
import com.thisnote.ext.baidusync.repository.BaiduSyncStateRepository;
import com.thisnote.plugin.api.event.FileChangeEvent;
import com.thisnote.plugin.api.util.FileHelper;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class BaiduSyncService {

    private static final String LEGACY_STAGING_DIRECTORY = "._thisnode_baidu_sync_staging_64fd90c1";
    private final BaiduSyncSettingsRepository settingsRepository;
    private final BaiduSyncStateRepository stateRepository;
    private final BaiduOAuthClient oauthClient;
    private final BaiduPanClient panClient;
    private final FileHelper fileHelper;
    private final Path notesRoot;
    private final Path baiduSyncDirectory;
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
        this.fileHelper = fileHelper;
        Path root = Path.of(dataRoot).toAbsolutePath().normalize();
        this.notesRoot = root.resolve("notes");
        this.baiduSyncDirectory = root.resolve("notes").resolve("plugins").resolve("baidu-sync");
    }

    @SneakyThrows
    @PostConstruct
    public void init() {
        BaiduSyncSettings settings = settingsRepository.get();
        validateSettings(settings);
        this.settings = settings;

        String remoteRoot = "/apps/" + settings.appName() + "/notes";
        this.remoteRoot = remoteRoot;

        initializeRemoteDirectories();
    }

    public void initializeRemoteDirectories() throws IOException {
        BaiduSyncSettings settings = settingsRepository.get();
        if (settings.appName().isBlank() || (settings.accessToken().isBlank() && settings.refreshToken().isBlank())) {
            log.error("param is null appName:{} accessToken:{} refreshToken:{}", settings.appName(), settings.accessToken(), settings.refreshToken());
            return;
        }

        List<String> remoteDirectories = (Files.exists(notesRoot) ? scanLocalSnapshot(notesRoot, baiduSyncDirectory).directories() : Set.<String>of()).stream()
                .sorted(Comparator.comparingInt(directory -> directory.split("/").length))
                .map(directory -> "/apps/" + settings.appName() + "/notes/" + directory)
                .toList();

        if (!remoteDirectoryInitialization.isDone()) {
            return;
        }
        remoteDirectoryInitialization = CompletableFuture.runAsync(() -> {
            String accessToken = oauthClient.accessToken();
            createRemoteDirectory(accessToken, "/apps", settings.appName() + "/notes");
            remoteDirectories.forEach(directory -> createRemoteDirectoryPath(accessToken, directory));
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
            log.warn("同步进行中，跳过同步基线清理");
            return;
        }
        stateRepository.save(BaiduSyncState.empty(notesRoot.toString(), remoteRoot));
        status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
        log.info("百度网盘同步基线已重置（授权凭据保留）");
    }

    public BaiduSyncPreview preview() throws IOException {
        ensureSyncIdle();
        LocalSnapshot localSnapshot = scanLocalSnapshot(notesRoot, baiduSyncDirectory);
        Map<String, LocalItem> local = localSnapshot.files();

        String accessToken = oauthClient.accessToken();
        RemoteSnapshot remote = scanRemote(accessToken, remoteRoot);

        BaiduSyncState baseline = stateRepository.get(notesRoot.toString(), remoteRoot);
        List<FileChange> changes = planFileChanges(local, remote, baseline);
        int consistent = countConsistentFiles(local, remote.files());
        return createPreview(consistent, changes);
    }

    private int countConsistentFiles(Map<String, LocalItem> local,
                                     Map<String, BaiduPanEntry> remoteFiles) {
        int consistent = 0;
        for (Map.Entry<String, LocalItem> entry : local.entrySet()) {
            BaiduPanEntry remoteItem = remoteFiles.get(entry.getKey());
            if (remoteItem != null && matchingFingerprint(entry.getValue(), remoteItem) != null) {
                consistent++;
            }
        }
        return consistent;
    }

    private static FileFingerprint matchingFingerprint(LocalItem local,
                                                       BaiduPanEntry remote) {
        FileFingerprint fingerprint = FileFingerprint.of(local.size(), local.modifiedAt());
        return fingerprint.equals(FileFingerprint.of(remote.size(), remote.modifiedAt())) ? fingerprint : null;
    }

    private BaiduSyncPreview createPreview(int consistent,
                                           List<FileChange> changes) {
        List<BaiduSyncPreviewItem> items = new ArrayList<>();
        int uploads = 0, downloads = 0, deletions = 0, overwrites = 0, conflicts = 0;
        for (FileChange change : changes) {
            if (change.action() == FileAction.UPLOAD) {
                uploads++;
            } else if (change.action() == FileAction.DOWNLOAD) {
                downloads++;
            } else {
                deletions++;
            }
            if (change.overwrite()) {
                overwrites++;
            }
            if (change.conflict()) {
                conflicts++;
            }
            items.add(new BaiduSyncPreviewItem(change.relative(), change.previewAction(), change.conflict()));
        }
        return new BaiduSyncPreview(true, uploads, downloads, deletions, overwrites, conflicts, consistent, items);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledSync() {
        if (settingsRepository.get().autoSync()) {
            startSync();
        }
    }

    private void sync() {
        SyncCounters counters = new SyncCounters();
        try {
            String accessToken = oauthClient.accessToken();

            LocalSnapshot localSnapshot = scanLocalSnapshot(notesRoot, baiduSyncDirectory);
            Map<String, LocalItem> local = localSnapshot.files();
            RemoteSnapshot remote = scanRemote(accessToken, remoteRoot);
            BaiduSyncState baseline = stateRepository.get(notesRoot.toString(), remoteRoot);

            List<FileChange> changes = planFileChanges(local, remote, baseline);
            if (CollectionUtil.isEmpty(changes)) {
                status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
                return;
            }
            log.info("change size:{}", CollectionUtil.size(changes));

            counters.conflicts = (int) changes.stream().filter(FileChange::conflict).count();
            uploadChanges(accessToken, remoteRoot, changes.stream().filter(change -> change.action() == FileAction.UPLOAD).toList(), counters);
            downloadChanges(accessToken, notesRoot, changes.stream().filter(change -> change.action() == FileAction.DOWNLOAD).toList(), counters);
            deleteLocalChanges(changes.stream().filter(change -> change.action() == FileAction.DELETE_LOCAL).toList(), counters);
            deleteRemoteChanges(accessToken, changes.stream().filter(change -> change.action() == FileAction.DELETE_REMOTE).toList(), counters);

            // 传入同步前已知的全部路径而不是仅 changes：双端一致但基线缺失的路径不会进入 changes，
            // 只有覆盖全部路径，逐路径写基线的结果才等价于原先的全量重建。
            Set<String> allPaths = Stream.of(localSnapshot.files.keySet(), remote.files.keySet(), baseline.files().keySet()).flatMap(Set::stream).collect(Collectors.toSet());
            saveChangedFileBaseline(accessToken, notesRoot, remoteRoot, allPaths);
            status = new BaiduSyncStatus(false, counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts, "");
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
        status = new BaiduSyncStatus(false, counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts, failureMessage);
    }

    private void syncChangedFiles(Set<Path> changedPaths) {
        SyncCounters counters = new SyncCounters();
        try {
            Set<String> relativePaths = relativeChangedPaths(notesRoot, changedPaths);
            if (relativePaths.isEmpty()) {
                status = new BaiduSyncStatus(false, 0, 0, 0, 0, "");
                log.debug("relativePaths size:{}", CollectionUtil.size(relativePaths));
                return;
            }

            // 仅按变更路径查询，不扫描整个本地笔记树或网盘目录树。
            Map<String, LocalItem> localFiles = localFilesForPaths(notesRoot, relativePaths);

            String accessToken = oauthClient.accessToken();
            Map<String, BaiduPanEntry> remoteFiles = listRemoteFilesForPaths(accessToken, remoteRoot, relativePaths);
            List<FileChange> changes = planChangedFileOperations(relativePaths, localFiles, remoteFiles);

            uploadChanges(accessToken, remoteRoot, changes.stream().filter(change -> change.action() == FileAction.UPLOAD).toList(), counters);
            deleteRemoteChanges(accessToken, changes.stream().filter(change -> change.action() == FileAction.DELETE_REMOTE).toList(), counters);
            saveChangedFileBaseline(accessToken, notesRoot, remoteRoot, relativePaths);
            status = new BaiduSyncStatus(false, counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts, "");
            log.warn("百度网盘文件变更同步完成：上传 {} 删除 {}（路径{}）", counters.uploaded, counters.deleted, JSON.toJSONString(changedPaths));
        } catch (Exception e) {
            log.info("百度网盘文件变更同步异常", e);
            failSync(counters, e);
        }
    }

    private Set<String> relativeChangedPaths(Path notesRoot,
                                             Set<Path> changedPaths) {
        Set<String> relatives = new LinkedHashSet<>();
        Path normalizedBaiduSyncDirectory = baiduSyncDirectory.toAbsolutePath().normalize();
        for (Path changedPath : changedPaths) {
            Path path = changedPath.toAbsolutePath().normalize();
            if (!path.startsWith(notesRoot) || path.equals(notesRoot) || (normalizedBaiduSyncDirectory.startsWith(notesRoot) && path.startsWith(normalizedBaiduSyncDirectory))) {
                continue;
            }
            String relative = notesRoot.relativize(path).toString().replace('\\', '/');
            relatives.add(relative);
        }
        return relatives;
    }

    private Map<String, LocalItem> localFilesForPaths(Path notesRoot,
                                                      Set<String> relativePaths) throws IOException {
        Map<String, LocalItem> localFiles = new LinkedHashMap<>();
        for (String relative : relativePaths) {
            Path file = resolveLocal(notesRoot, relative);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                continue;
            }
            if (!attributes.isRegularFile()) {
                throw new IllegalStateException("同步目标不是普通文件：" + relative);
            }
            localFiles.put(relative, new LocalItem(file, relative, attributes.lastModifiedTime().toMillis(), attributes.size()));
        }
        return localFiles;
    }

    private Map<String, BaiduPanEntry> listRemoteFilesForPaths(String accessToken,
                                                               String remoteRoot,
                                                               Set<String> relativePaths) {
        Map<String, Set<String>> pathsByParent = new LinkedHashMap<>();
        for (String relative : relativePaths) {
            int slash = relative.lastIndexOf('/');
            String parent = slash < 0 ? "" : relative.substring(0, slash);
            String remoteParent = parent.isBlank() ? remoteRoot : remoteRoot + "/" + parent;
            pathsByParent.computeIfAbsent(remoteParent, ignored -> new LinkedHashSet<>()).add(relative);
        }

        Map<String, BaiduPanEntry> remoteFiles = new LinkedHashMap<>();
        String remotePrefix = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";
        for (Map.Entry<String, Set<String>> parentEntry : pathsByParent.entrySet()) {
            List<BaiduPanEntry> entries;
            try {
                entries = panClient.listDirectory(accessToken, parentEntry.getKey());
            } catch (BaiduPanException e) {
                if (e.errno() == -9) {
                    continue;
                }
                throw e;
            }
            for (BaiduPanEntry entry : entries) {
                String relative = entry.path().startsWith(remotePrefix) ? entry.path().substring(remotePrefix.length()) : "";
                if (!parentEntry.getValue().contains(relative)) {
                    continue;
                }
                if (entry.directory()) {
                    throw new IllegalStateException("本地文件与百度网盘目录路径冲突，请先手动处理：" + relative);
                }
                remoteFiles.put(relative, entry);
            }
        }
        return remoteFiles;
    }

    private List<FileChange> planChangedFileOperations(Set<String> relativePaths,
                                                       Map<String, LocalItem> localFiles,
                                                       Map<String, BaiduPanEntry> remoteFiles) {
        List<FileChange> changes = new ArrayList<>();
        for (String relative : relativePaths) {
            LocalItem local = localFiles.get(relative);
            BaiduPanEntry remote = remoteFiles.get(relative);
            // Resolve a burst of create/update/delete events from the final local state.
            // 例如重命名 a.md 为 b.md 后，a.md 本地不存在则删云端旧文件，b.md 存在则上传新文件。
            if (local != null) {
                changes.add(new FileChange(relative, FileAction.UPLOAD, local, remote, remote != null, false));
            } else if (remote != null) {
                changes.add(new FileChange(relative, FileAction.DELETE_REMOTE, null, remote, false, false));
            }
        }
        return changes;
    }

    private void saveChangedFileBaseline(String accessToken,
                                         Path notesRoot,
                                         String remoteRoot,
                                         Set<String> relativePaths) throws IOException {
        Map<String, LocalItem> localFiles = localFilesForPaths(notesRoot, relativePaths);
        Map<String, BaiduPanEntry> remoteFiles = listRemoteFilesForPaths(accessToken, remoteRoot, relativePaths);
        // 重新读取传输后的两端状态；只有仍匹配的路径才写入基线。
        // 例如上传期间本地文件又被编辑、两端指纹不一致时，移除旧基线，下一轮再按无基线差异决策。
        BaiduSyncState current = stateRepository.get(notesRoot.toString(), remoteRoot);
        Map<String, String> files = new LinkedHashMap<>(current.files());
        Set<String> directories = new LinkedHashSet<>(current.directories());

        for (String relative : relativePaths) {
            LocalItem local = localFiles.get(relative);
            BaiduPanEntry remote = remoteFiles.get(relative);
            FileFingerprint fingerprint = local == null || remote == null ? null : matchingFingerprint(local, remote);
            if (fingerprint != null) {
                files.put(relative, fingerprint.toStorage());
                addParentDirectories(local.parent(), directories);
            } else {
                files.remove(relative);
            }
        }

        stateRepository.save(new BaiduSyncState(notesRoot.toString(), remoteRoot, System.currentTimeMillis(), files, directories));
    }

    private void addParentDirectories(String parent,
                                      Set<String> directories) {
        if (parent.isBlank()) {
            return;
        }
        String current = "";
        for (String segment : parent.split("/")) {
            current = current.isEmpty() ? segment : current + "/" + segment;
            directories.add(current);
        }
    }

    private void uploadChanges(String accessToken,
                               String remoteRoot,
                               List<FileChange> changes,
                               SyncCounters counters) {
        for (FileChange change : changes) {
            log.debug("上传 {}", change.relative());
            panClient.upload(accessToken, change.local().file(), remoteRoot + "/" + change.relative());
            counters.uploaded++;
        }
    }

    private void downloadChanges(String accessToken,
                                 Path notesRoot,
                                 List<FileChange> changesToDownload,
                                 SyncCounters counters) {
        List<BaiduPanEntry> entries = changesToDownload.stream().map(FileChange::remote).toList();
        Map<Long, String> downloadUrls = panClient.downloadUrls(accessToken, entries);
        for (FileChange change : changesToDownload) {
            log.debug("下载 {}", change.relative());
            panClient.downloadWithUrl(accessToken, change.remote(), resolveLocal(notesRoot, change.relative()), downloadUrls.get(change.remote().fsId()));
            counters.downloaded++;
        }
    }

    private void deleteLocalChanges(List<FileChange> changes,
                                    SyncCounters counters) {
        for (FileChange change : changes) {
            log.debug("删除本地 {}", change.relative());
            if (fileHelper.fileDeleted(change.local().file())) {
                counters.deleted++;
            }
        }
    }

    private void deleteRemoteChanges(String accessToken,
                                     List<FileChange> changesToDelete,
                                     SyncCounters counters) {
        for (int start = 0; start < changesToDelete.size(); start += BaiduPanClient.FILE_MANAGER_BATCH_SIZE) {
            List<FileChange> batch = changesToDelete.subList(start, Math.min(start + BaiduPanClient.FILE_MANAGER_BATCH_SIZE, changesToDelete.size()));
            List<String> paths = batch.stream().map(change -> change.remote().path()).toList();
            List<BaiduPanClient.FileManagerFailure> failures = panClient.deleteBatch(accessToken, paths);
            Set<String> failedPaths = new LinkedHashSet<>();
            for (BaiduPanClient.FileManagerFailure failure : failures) {
                String failedPath = failure.path();
                if (failedPath == null || !paths.contains(failedPath)) {
                    if (paths.size() != 1) {
                        throw new IllegalStateException("无法对应百度网盘批量删除的失败项：" + failedPath);
                    }
                    failedPath = paths.get(0);
                }
                failedPaths.add(failedPath);
            }
            counters.deleted += batch.size() - failedPaths.size();
            if (!failures.isEmpty()) {
                BaiduPanClient.FileManagerFailure failure = failures.get(0);
                throw new IllegalStateException("删除百度网盘文件失败：" + failure.exception().getMessage(), failure.exception());
            }
        }
    }

    private LocalSnapshot scanLocalSnapshot(Path root,
                                            Path excluded) throws IOException {
        Map<String, LocalItem> files = new LinkedHashMap<>();
        Set<String> directories = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                                                     BasicFileAttributes attrs) {
                if (dir.toAbsolutePath().normalize().equals(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(root)) {
                    directories.add(root.relativize(dir).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    files.put(relative, new LocalItem(file, relative, attrs.lastModifiedTime().toMillis(), attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new LocalSnapshot(files, directories);
    }

    private RemoteSnapshot scanRemote(String accessToken,
                                      String remoteRoot) {
        Map<String, BaiduPanEntry> files = new LinkedHashMap<>();
        Set<String> directoriesSeen = new LinkedHashSet<>();
        ArrayDeque<String> directories = new ArrayDeque<>();
        String remotePrefix = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";
        directories.add(remoteRoot);
        while (!directories.isEmpty()) {
            String directory = directories.removeFirst();
            for (BaiduPanEntry item : panClient.listDirectory(accessToken, directory)) {
                String relative = item.path().startsWith(remotePrefix) ? item.path().substring(remotePrefix.length()) : "";
                if (relative.isBlank()) {
                    continue;
                }
                if (relative.equals(LEGACY_STAGING_DIRECTORY)) {
                    if (!item.directory()) {
                        throw new IllegalStateException("百度网盘历史临时路径已被文件占用：" + LEGACY_STAGING_DIRECTORY);
                    }
                    continue;
                }
                if (item.directory()) {
                    directoriesSeen.add(relative);
                    directories.addLast(item.path());
                } else {
                    files.put(relative, item);
                }
            }
        }
        return new RemoteSnapshot(files, directoriesSeen);
    }

    /**
     * 将基线路径也纳入比较，才能识别当前快照中已经消失的文件。
     * 例如指纹为 size:mtime秒：基线 100:10、本地仍为 100:10、云端缺失 -> 删除本地；
     * 本地变为 120:11 或该路径没有基线 -> 上传本地文件。
     */
    private List<FileChange> planFileChanges(Map<String, LocalItem> local,
                                             RemoteSnapshot remote,
                                             BaiduSyncState baseline) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(local.keySet());
        paths.addAll(remote.files().keySet());
        List<FileChange> changes = new ArrayList<>();

        for (String relative : paths) {
            LocalItem localItem = local.get(relative);
            BaiduPanEntry remoteItem = remote.files().get(relative);
            FileFingerprint previousFingerprint = FileFingerprint.parse(baseline.files().get(relative));
            if (localItem != null && remoteItem != null) {
                FileFingerprint localFingerprint = FileFingerprint.of(localItem.size(), localItem.modifiedAt());
                FileFingerprint remoteFingerprint = FileFingerprint.of(remoteItem.size(), remoteItem.modifiedAt());
                if (localFingerprint.equals(remoteFingerprint)) {
                    continue;
                }
                boolean localChanged = previousFingerprint == null || !localFingerprint.equals(previousFingerprint);
                boolean remoteChanged = previousFingerprint == null || !remoteFingerprint.equals(previousFingerprint);
                boolean conflict = previousFingerprint != null && localChanged && remoteChanged;
                boolean localWins;
                if (previousFingerprint == null || conflict) {
                    // 例如基线 100:10、本地 120:11、云端 110:12：双方都变更，标记冲突并以较新的秒级修改时间决胜。
                    localWins = localFingerprint.modifiedAtSeconds() >= remoteFingerprint.modifiedAtSeconds();
                } else {
                    localWins = localChanged || !remoteChanged;
                }
                changes.add(new FileChange(relative, localWins ? FileAction.UPLOAD : FileAction.DOWNLOAD, localItem, remoteItem, true, conflict));
                continue;
            }
            if (localItem != null) {
                FileFingerprint localFingerprint = FileFingerprint.of(localItem.size(), localItem.modifiedAt());
                boolean localChanged = previousFingerprint == null || !localFingerprint.equals(previousFingerprint);
                changes.add(new FileChange(relative, localChanged ? FileAction.UPLOAD : FileAction.DELETE_LOCAL, localItem, null, false, false));
                continue;
            }
            FileFingerprint remoteFingerprint = FileFingerprint.of(remoteItem.size(), remoteItem.modifiedAt());
            boolean remoteChanged = previousFingerprint == null || !remoteFingerprint.equals(previousFingerprint);
            changes.add(new FileChange(relative, remoteChanged ? FileAction.DOWNLOAD : FileAction.DELETE_REMOTE, null, remoteItem, false, false));
        }
        return changes;
    }

    private void createRemoteDirectory(String accessToken,
                                       String remoteRoot,
                                       String relative) {
        if (relative.isBlank()) {
            return;
        }
        String path = remoteRoot;
        for (String segment : relative.split("/")) {
            path += "/" + segment;
            createRemoteDirectoryPath(accessToken, path);
        }
    }

    // 目标目录已存在（errno -8）视为成功；其他创建错误向上抛出。
    private void createRemoteDirectoryPath(String accessToken,
                                           String path) {
        try {
            panClient.createDirectory(accessToken, path);
        } catch (BaiduPanException e) {
            if (e.errno() != -8) {
                throw new IllegalStateException("创建百度网盘目录失败：" + path + "；" + e.getMessage(), e);
            }
        }
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

    private Path resolveLocal(Path root,
                              String relative) {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("网盘路径越界：" + relative);
        }
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("同步路径包含符号链接，已跳过：" + relative);
            }
        }
        return target;
    }

    record FileFingerprint(long size, long modifiedAtSeconds) {

        static FileFingerprint of(long size,
                                  long modifiedAtMillis) {
            return new FileFingerprint(size, Math.floorDiv(modifiedAtMillis, 1000L));
        }

        /**
         * 基线持久化格式为 size:mtimeSeconds；旧 MD5 等无法解析的值视为缺失。
         */
        static FileFingerprint parse(String value) {
            if (value == null) {
                return null;
            }
            int separator = value.indexOf(':');
            if (separator <= 0 || separator != value.lastIndexOf(':')) {
                return null;
            }
            try {
                long size = Long.parseLong(value.substring(0, separator));
                long modifiedAtSeconds = Long.parseLong(value.substring(separator + 1));
                return size < 0 ? null : new FileFingerprint(size, modifiedAtSeconds);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String toStorage() {
            return size + ":" + modifiedAtSeconds;
        }
    }

    private static final class SyncCounters {
        private int uploaded;
        private int downloaded;
        private int deleted;
        private int conflicts;
    }

    private record LocalSnapshot(Map<String, LocalItem> files, Set<String> directories) {
    }

    private record LocalItem(Path file, String relative, long modifiedAt, long size) {
        private String parent() {
            int slash = relative.lastIndexOf('/');
            return slash < 0 ? "" : relative.substring(0, slash);
        }
    }

    private record RemoteSnapshot(Map<String, BaiduPanEntry> files, Set<String> directories) {
    }

    private enum FileAction {
        UPLOAD, DOWNLOAD, DELETE_LOCAL, DELETE_REMOTE
    }

    private record FileChange(String relative, FileAction action, LocalItem local, BaiduPanEntry remote, boolean overwrite, boolean conflict) {
        // 动作字符串与界面侧的预览展示约定。
        private String previewAction() {
            return switch (action) {
                case UPLOAD -> overwrite ? "overwrite-remote" : "upload";
                case DOWNLOAD -> overwrite ? "overwrite-local" : "download";
                case DELETE_LOCAL -> "delete-local";
                case DELETE_REMOTE -> "delete-remote";
            };
        }
    }
}
