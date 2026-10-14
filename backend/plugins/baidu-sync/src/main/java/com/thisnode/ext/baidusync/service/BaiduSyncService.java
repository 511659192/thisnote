package com.thisnode.ext.baidusync.service;

import com.thisnode.ext.baidusync.client.BaiduOAuthClient;
import com.thisnode.ext.baidusync.client.BaiduPanClient;
import com.thisnode.ext.baidusync.client.BaiduPanEntry;
import com.thisnode.ext.baidusync.client.BaiduPanException;
import com.thisnode.ext.baidusync.model.BaiduSyncConnectionProbe;
import com.thisnode.ext.baidusync.model.BaiduSyncPreview;
import com.thisnode.ext.baidusync.model.BaiduSyncPreviewItem;
import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.ext.baidusync.model.BaiduSyncState;
import com.thisnode.ext.baidusync.model.BaiduSyncStatus;
import com.thisnode.ext.baidusync.repository.BaiduSyncSettingsRepository;
import com.thisnode.ext.baidusync.repository.BaiduSyncStateRepository;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class BaiduSyncService {

    private static final String STAGING_DIRECTORY = "._thisnode_baidu_sync_staging_64fd90c1";
    private static final int UPLOAD_THREAD_COUNT = 8;
    private static final int UPLOAD_QUEUE_CAPACITY = 256;
    private static final int MAX_UPLOADS_IN_FLIGHT = UPLOAD_THREAD_COUNT + UPLOAD_QUEUE_CAPACITY;

    private final BaiduSyncSettingsRepository settingsRepository;
    private final BaiduSyncStateRepository stateRepository;
    private final BaiduOAuthClient oauthClient;
    private final BaiduPanClient panClient;
    private final Path dataRoot;
    private final Path pluginDirectory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "baidu-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor uploadExecutor = new ThreadPoolExecutor(
            UPLOAD_THREAD_COUNT,
            UPLOAD_THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(UPLOAD_QUEUE_CAPACITY),
            task -> {
                Thread thread = new Thread(task, "baidu-sync-upload");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicReference<BaiduSyncStatus> status = new AtomicReference<>(BaiduSyncStatus.idle());

    public BaiduSyncService(BaiduSyncSettingsRepository settingsRepository,
                            BaiduSyncStateRepository stateRepository,
                            BaiduOAuthClient oauthClient,
                            BaiduPanClient panClient,
                            @Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.settingsRepository = settingsRepository;
        this.stateRepository = stateRepository;
        this.oauthClient = oauthClient;
        this.panClient = panClient;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
        this.pluginDirectory = PluginDataPathUtils.pluginDataDirectory(this.dataRoot, "baidu-sync");
    }

    public BaiduSyncStatus startSync() {
        BaiduSyncStatus current = status.get();
        if (current.running()) {
            return current;
        }
        long startedAt = System.currentTimeMillis();
        BaiduSyncStatus running = new BaiduSyncStatus(true, startedAt, current.completedAt(), 0, 0, 0, 0, "");
        if (!status.compareAndSet(current, running)) {
            return status.get();
        }
        log.info("百度网盘同步开始");
        try {
            executor.execute(() -> sync(running));
        } catch (RuntimeException e) {
            status.set(new BaiduSyncStatus(false, startedAt, current.completedAt(), 0, 0, 0, 0, "无法启动同步任务"));
        }
        return status.get();
    }

    public BaiduSyncStatus status() {
        return status.get();
    }

    public BaiduSyncConnectionProbe probeConnection() {
        ensureSyncIdle();
        BaiduSyncSettings settings = settingsRepository.get();
        validateSettings(settings);
        String remoteRoot = remoteRoot(settings.appName());
        String accessToken = oauthClient.accessToken();
        if (!panClient.directoryExists(accessToken, remoteRoot)) {
            return new BaiduSyncConnectionProbe(remoteRoot, false, 0, 0);
        }
        List<BaiduPanEntry> items = panClient.listDirectory(accessToken, remoteRoot);
        int files = (int) items.stream().filter(item -> !item.directory()).count();
        int directories = (int) items.stream().filter(BaiduPanEntry::directory).count();
        return new BaiduSyncConnectionProbe(remoteRoot, true,
                files, directories);
    }

    public BaiduSyncPreview preview() throws IOException {
        ensureSyncIdle();
        BaiduSyncSettings settings = settingsRepository.get();
        validateSettings(settings);
        String remoteRoot = remoteRoot(settings.appName());
        String accessToken = oauthClient.accessToken();
        Path notesRoot = PluginDataPathUtils.configuredNotesRoot(dataRoot.resolve("notes"));
        if (Files.exists(notesRoot) && !Files.isDirectory(notesRoot)) {
            throw new IllegalStateException("笔记根目录不是文件夹：" + notesRoot);
        }

        Map<String, LocalItem> local = Files.isDirectory(notesRoot)
                ? scanLocal(notesRoot, pluginDirectory)
                : Map.of();
        Set<String> localDirectories = Files.isDirectory(notesRoot)
                ? scanLocalDirectories(notesRoot, pluginDirectory)
                : Set.of();
        if (local.containsKey(STAGING_DIRECTORY) || localDirectories.contains(STAGING_DIRECTORY)) {
            throw new IllegalStateException("笔记根目录包含百度同步保留目录，请先重命名：" + STAGING_DIRECTORY);
        }

        boolean remoteExists = panClient.directoryExists(accessToken, remoteRoot);
        RemoteSnapshot remote = remoteExists
                ? scanRemote(accessToken, remoteRoot)
                : new RemoteSnapshot(Map.of(), Set.of());
        validatePathTypes(local.keySet(), localDirectories, remote);
        BaiduSyncState baseline = stateRepository.get(notesRoot.toString(), remoteRoot);
        List<FileChange> changes = planFileChanges(local, remote, baseline);
        List<BaiduSyncPreviewItem> items = new ArrayList<>();
        int uploads = 0;
        int downloads = 0;
        int deletions = 0;
        int overwrites = 0;
        int conflicts = 0;
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
        return new BaiduSyncPreview(remoteExists, uploads, downloads, deletions,
                overwrites, conflicts, items);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledSync() {
        if (settingsRepository.get().autoSync()) {
            startSync();
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        uploadExecutor.shutdownNow();
    }

    private void sync(BaiduSyncStatus initial) {
        SyncCounters counters = new SyncCounters();
        try {
            BaiduSyncSettings settings = settingsRepository.get();
            validateSettings(settings);
            String accessToken = oauthClient.accessToken();
            Path notesRoot = PluginDataPathUtils.configuredNotesRoot(dataRoot.resolve("notes"));
            Files.createDirectories(notesRoot);
            String remoteRoot = remoteRoot(settings.appName());
            ensureRemoteRoot(accessToken, remoteRoot);

            Map<String, LocalItem> local = scanLocal(notesRoot, pluginDirectory);
            Set<String> localDirectories = scanLocalDirectories(notesRoot, pluginDirectory);
            if (local.containsKey(STAGING_DIRECTORY) || localDirectories.contains(STAGING_DIRECTORY)) {
                throw new IllegalStateException("笔记根目录包含百度同步保留目录，请先重命名：" + STAGING_DIRECTORY);
            }
            cleanRemoteStaging(accessToken, remoteRoot);
            RemoteSnapshot remote = scanRemote(accessToken, remoteRoot);
            validatePathTypes(local.keySet(), localDirectories, remote);
            BaiduSyncState baseline = stateRepository.get(notesRoot.toString(), remoteRoot);
            List<FileChange> changes = planFileChanges(local, remote, baseline);
            counters.conflicts = (int) changes.stream().filter(FileChange::conflict).count();
            uploadChanges(accessToken, remoteRoot, changes, counters)
                    .thenRunAsync(() -> {
                        try {
                            completeSyncAfterUploads(initial, counters, accessToken, notesRoot,
                                    remoteRoot, changes, remote, baseline);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, executor)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            failSync(initial, counters, error);
                        }
                    });
        } catch (Exception e) {
            failSync(initial, counters, e);
        }
    }

    private void completeSyncAfterUploads(BaiduSyncStatus initial,
                                          SyncCounters counters,
                                          String accessToken,
                                          Path notesRoot,
                                          String remoteRoot,
                                          List<FileChange> changes,
                                          RemoteSnapshot remote,
                                          BaiduSyncState baseline) throws IOException {
        downloadChanges(accessToken, notesRoot, changes, counters);
        for (FileChange change : changes) {
            if (change.action() == FileAction.DELETE_LOCAL) {
                log.debug("删除本地 {}", change.relative());
                Files.deleteIfExists(change.local().file());
                counters.deleted++;
            }
        }
        deleteRemoteChanges(accessToken, changes, counters);

        Set<String> localFilesAfterTransfer = scanLocal(notesRoot, pluginDirectory).keySet();
        syncDirectories(accessToken, notesRoot, remoteRoot, localFilesAfterTransfer,
                remote.directories(), baseline.directories());
        Map<String, LocalItem> finalLocal = scanLocal(notesRoot, pluginDirectory);
        RemoteSnapshot finalRemote = scanRemote(accessToken, remoteRoot);
        Map<String, String> finalFiles = new LinkedHashMap<>();
        for (Map.Entry<String, LocalItem> entry : finalLocal.entrySet()) {
            BaiduPanEntry cloud = finalRemote.files().get(entry.getKey());
            if (cloud != null && entry.getValue().md5().equalsIgnoreCase(cloud.md5())) {
                finalFiles.put(entry.getKey(), entry.getValue().md5());
            }
        }
        Set<String> directories = new LinkedHashSet<>();
        finalLocal.values().stream().map(LocalItem::parent).filter(value -> !value.isEmpty()).forEach(directories::add);
        directories.addAll(scanLocalDirectories(notesRoot, pluginDirectory));
        stateRepository.save(new BaiduSyncState(notesRoot.toString(), remoteRoot, System.currentTimeMillis(),
                finalFiles, directories));
        status.set(new BaiduSyncStatus(false, initial.startedAt(), System.currentTimeMillis(),
                counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts, ""));
        log.info("百度网盘同步完成：上传 {} 下载 {} 删除 {} 冲突 {}（耗时 {} ms）",
                counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts,
                System.currentTimeMillis() - initial.startedAt());
    }

    private void failSync(BaiduSyncStatus initial,
                          SyncCounters counters,
                          Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        Exception failure = cause instanceof Exception exception
                ? exception
                : new IllegalStateException("百度网盘同步失败", cause);
        log.warn("百度网盘同步失败：{}", message(failure), failure);
        status.set(new BaiduSyncStatus(false, initial.startedAt(), System.currentTimeMillis(),
                counters.uploaded, counters.downloaded, counters.deleted, counters.conflicts, message(failure)));
    }

    private CompletableFuture<Void> uploadChanges(String accessToken,
                                                  String remoteRoot,
                                                  List<FileChange> changes,
                                                  SyncCounters counters) {
        List<FileChange> changesToUpload = changes.stream()
                .filter(change -> change.action() == FileAction.UPLOAD)
                .toList();
        if (changesToUpload.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        createRemoteDirectory(accessToken, remoteRoot, STAGING_DIRECTORY);
        Set<String> uploadDirectories = new LinkedHashSet<>();
        for (FileChange change : changesToUpload) {
            String parent = change.local().parent();
            if (parent.isBlank()) {
                continue;
            }
            String path = remoteRoot;
            for (String segment : parent.split("/")) {
                path += "/" + segment;
                uploadDirectories.add(path);
            }
        }
        for (String directory : uploadDirectories) {
            createRemoteDirectoryPath(accessToken, directory);
        }

        return uploadAllAsync(accessToken, remoteRoot, changesToUpload)
                .thenAcceptAsync(uploadPlans -> {
                    for (int start = 0; start < uploadPlans.size();
                         start += BaiduPanClient.FILE_MANAGER_BATCH_SIZE) {
                        List<UploadPlan> moveBatch = uploadPlans.subList(start,
                                Math.min(start + BaiduPanClient.FILE_MANAGER_BATCH_SIZE, uploadPlans.size()));
                        moveUploadedBatch(accessToken, moveBatch, counters);
                    }
                }, executor)
                .thenRunAsync(() -> panClient.delete(
                        accessToken, remoteRoot + "/" + STAGING_DIRECTORY), executor);
    }

    private CompletableFuture<List<UploadPlan>> uploadAllAsync(String accessToken,
                                                               String remoteRoot,
                                                               List<FileChange> changes) {
        UploadDispatch dispatch = new UploadDispatch(accessToken, remoteRoot, changes);
        dispatchUploads(dispatch);
        return dispatch.completion;
    }

    private void dispatchUploads(UploadDispatch dispatch) {
        while (dispatch.failure == null
                && dispatch.nextIndex < dispatch.changes.size()
                && dispatch.inFlight < MAX_UPLOADS_IN_FLIGHT) {
            int index = dispatch.nextIndex++;
            FileChange change = dispatch.changes.get(index);
            dispatch.inFlight++;
            try {
                CompletableFuture.supplyAsync(() -> uploadOne(
                                dispatch.accessToken, dispatch.remoteRoot, change), uploadExecutor)
                        .whenComplete((plan, error) -> {
                            try {
                                executor.execute(() -> uploadCompleted(dispatch, index, plan, error));
                            } catch (RejectedExecutionException e) {
                                dispatch.completion.completeExceptionally(e);
                            }
                        });
            } catch (RejectedExecutionException e) {
                dispatch.nextIndex--;
                dispatch.inFlight--;
                if (uploadExecutor.isShutdown()) {
                    dispatch.failure = new IllegalStateException("百度网盘上传线程池已关闭", e);
                } else if (dispatch.inFlight == 0) {
                    dispatch.failure = new IllegalStateException("百度网盘上传任务无法入队", e);
                }
                // A full queue may be a brief handoff race; the next completion callback retries dispatch.
                break;
            }
        }

        if (dispatch.failure != null && dispatch.inFlight == 0) {
            dispatch.completion.completeExceptionally(dispatch.failure);
        } else if (dispatch.failure == null
                && dispatch.nextIndex == dispatch.changes.size()
                && dispatch.inFlight == 0) {
            dispatch.completion.complete(List.of(dispatch.plans));
        }
    }

    private UploadPlan uploadOne(String accessToken,
                                 String remoteRoot,
                                 FileChange change) {
        log.debug("上传 {}", change.relative());
        String stagingPath = remoteRoot + "/" + STAGING_DIRECTORY
                + "/upload-" + UUID.randomUUID() + ".tmp";
        try {
            panClient.upload(accessToken, change.local().file(), stagingPath);
        } catch (RuntimeException e) {
            throw new IllegalStateException("上传百度网盘文件失败：" + change.relative(), e);
        }
        String destinationDirectory = change.local().parent().isBlank()
                ? remoteRoot
                : remoteRoot + "/" + change.local().parent();
        String filename = change.relative().substring(change.relative().lastIndexOf('/') + 1);
        return new UploadPlan(new BaiduPanClient.FileManagerMove(
                stagingPath, destinationDirectory, filename));
    }

    private void uploadCompleted(UploadDispatch dispatch,
                                 int index,
                                 UploadPlan plan,
                                 Throwable error) {
        dispatch.inFlight--;
        if (error != null) {
            if (dispatch.failure == null) {
                dispatch.failure = error;
            }
        } else if (dispatch.failure == null) {
            dispatch.plans[index] = plan;
        }
        dispatchUploads(dispatch);
    }

    private void moveUploadedBatch(String accessToken,
                                   List<UploadPlan> stagedBatch,
                                   SyncCounters counters) {
        List<BaiduPanClient.FileManagerMove> moves = stagedBatch.stream().map(UploadPlan::move).toList();
        List<BaiduPanClient.FileManagerFailure> failures = panClient.moveOverwriteBatch(accessToken, moves);
        Map<UploadPlan, BaiduPanClient.FileManagerFailure> failuresByUpload = new LinkedHashMap<>();
        for (BaiduPanClient.FileManagerFailure failure : failures) {
            UploadPlan plan = matchUploadFailure(stagedBatch, failure);
            if (plan == null || failuresByUpload.put(plan, failure) != null) {
                throw new IllegalStateException("无法对应百度网盘批量移动的失败项：" + failure.path());
            }
        }
        counters.uploaded += stagedBatch.size() - failuresByUpload.size();
        for (Map.Entry<UploadPlan, BaiduPanClient.FileManagerFailure> entry : failuresByUpload.entrySet()) {
            BaiduPanClient.FileManagerFailure failure = entry.getValue();
            if (failure.apiErrno() == 12 && failure.itemErrno() == -8) {
                BaiduPanClient.FileManagerMove move = entry.getKey().move();
                panClient.moveOverwrite(accessToken, move.sourcePath(),
                        move.destinationDirectory(), move.filename());
                counters.uploaded++;
            } else {
                throw new IllegalStateException("百度网盘批量移动失败："
                        + failure.exception().getMessage(), failure.exception());
            }
        }
    }

    private UploadPlan matchUploadFailure(List<UploadPlan> batch,
                                          BaiduPanClient.FileManagerFailure failure) {
        for (UploadPlan plan : batch) {
            BaiduPanClient.FileManagerMove move = plan.move();
            String destinationPath = move.destinationDirectory() + "/" + move.filename();
            if (failure.path() != null
                    && (failure.path().equals(move.sourcePath()) || failure.path().equals(destinationPath))) {
                return plan;
            }
        }
        return batch.size() == 1 ? batch.get(0) : null;
    }

    private void downloadChanges(String accessToken,
                                 Path notesRoot,
                                 List<FileChange> changes,
                                 SyncCounters counters) {
        List<FileChange> changesToDownload = changes.stream()
                .filter(change -> change.action() == FileAction.DOWNLOAD)
                .toList();
        if (changesToDownload.isEmpty()) {
            return;
        }
        List<BaiduPanEntry> entries = changesToDownload.stream().map(FileChange::remote).toList();
        Map<Long, String> downloadUrls = panClient.downloadUrls(accessToken, entries);
        for (FileChange change : changesToDownload) {
            log.debug("下载 {}", change.relative());
            panClient.downloadWithUrl(accessToken, change.remote(),
                    resolveLocal(notesRoot, change.relative()), downloadUrls.get(change.remote().fsId()));
            counters.downloaded++;
        }
    }

    private void deleteRemoteChanges(String accessToken,
                                     List<FileChange> changes,
                                     SyncCounters counters) {
        List<FileChange> changesToDelete = changes.stream()
                .filter(change -> change.action() == FileAction.DELETE_REMOTE)
                .toList();
        for (int start = 0; start < changesToDelete.size(); start += BaiduPanClient.FILE_MANAGER_BATCH_SIZE) {
            List<FileChange> batch = changesToDelete.subList(start,
                    Math.min(start + BaiduPanClient.FILE_MANAGER_BATCH_SIZE, changesToDelete.size()));
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
                throw new IllegalStateException("删除百度网盘文件失败："
                        + failure.exception().getMessage(), failure.exception());
            }
        }
    }

    private Map<String, LocalItem> scanLocal(Path root,
                                             Path excluded) throws IOException {
        Map<String, LocalItem> result = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                                                     BasicFileAttributes attrs) {
                if (dir.toAbsolutePath().normalize().equals(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    String relative = relative(root, file);
                    result.put(relative, new LocalItem(file, relative, fileMd5(file),
                            Files.getLastModifiedTime(file).toMillis(), attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private Set<String> scanLocalDirectories(Path root,
                                             Path excluded) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                                                     BasicFileAttributes attrs) {
                if (dir.toAbsolutePath().normalize().equals(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(root)) {
                    result.add(relative(root, dir));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private RemoteSnapshot scanRemote(String accessToken,
                                      String remoteRoot) {
        Map<String, BaiduPanEntry> files = new LinkedHashMap<>();
        Set<String> directoriesSeen = new LinkedHashSet<>();
        ArrayDeque<String> directories = new ArrayDeque<>();
        directories.add(remoteRoot);
        while (!directories.isEmpty()) {
            String directory = directories.removeFirst();
            for (BaiduPanEntry item : panClient.listDirectory(accessToken, directory)) {
                String relative = remoteRelative(remoteRoot, item.path());
                if (relative.isBlank()) {
                    continue;
                }
                if (relative.equals(STAGING_DIRECTORY)) {
                    if (!item.directory()) {
                        throw new IllegalStateException("百度网盘保留路径已被文件占用：" + STAGING_DIRECTORY);
                    }
                    continue;
                }
                if (item.directory()) {
                    directoriesSeen.add(relative);
                    directories.addLast(item.path());
                } else {
                    if (item.md5().isBlank()) {
                        throw new IllegalStateException("百度网盘未返回文件摘要，已停止同步以避免误覆盖：" + item.path());
                    }
                    files.put(relative, item);
                }
            }
        }
        return new RemoteSnapshot(files, directoriesSeen);
    }

    private List<FileChange> planFileChanges(Map<String, LocalItem> local,
                                             RemoteSnapshot remote,
                                             BaiduSyncState baseline) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(local.keySet());
        paths.addAll(remote.files().keySet());
        paths.addAll(baseline.files().keySet());
        List<FileChange> changes = new ArrayList<>();

        for (String relative : paths) {
            LocalItem localItem = local.get(relative);
            BaiduPanEntry remoteItem = remote.files().get(relative);
            String previousHash = baseline.files().get(relative);
            if (localItem == null && remoteItem == null) {
                continue;
            }
            if (localItem != null && remoteItem != null) {
                if (localItem.md5().equalsIgnoreCase(remoteItem.md5())) {
                    continue;
                }
                boolean localChanged = previousHash == null
                        || !localItem.md5().equalsIgnoreCase(previousHash);
                boolean remoteChanged = previousHash == null
                        || !remoteItem.md5().equalsIgnoreCase(previousHash);
                boolean conflict = isConfirmedConflict(previousHash, localChanged, remoteChanged);
                boolean localWins;
                if (previousHash == null || conflict) {
                    localWins = localItem.modifiedAt() >= remoteItem.modifiedAt();
                } else {
                    localWins = localChanged || !remoteChanged;
                }
                changes.add(new FileChange(relative,
                        localWins ? FileAction.UPLOAD : FileAction.DOWNLOAD,
                        localItem, remoteItem, true, conflict));
                continue;
            }
            if (localItem != null) {
                boolean localChanged = previousHash == null
                        || !localItem.md5().equalsIgnoreCase(previousHash);
                changes.add(new FileChange(relative,
                        localChanged ? FileAction.UPLOAD : FileAction.DELETE_LOCAL,
                        localItem, null, false, false));
                continue;
            }
            boolean remoteChanged = previousHash == null
                    || !remoteItem.md5().equalsIgnoreCase(previousHash);
            changes.add(new FileChange(relative,
                    remoteChanged ? FileAction.DOWNLOAD : FileAction.DELETE_REMOTE,
                    null, remoteItem, false, false));
        }
        return changes;
    }

    static boolean isConfirmedConflict(String previousHash,
                                       boolean localChanged,
                                       boolean remoteChanged) {
        return previousHash != null && localChanged && remoteChanged;
    }

    private void validatePathTypes(Set<String> localFiles,
                                   Set<String> localDirectories,
                                   RemoteSnapshot remote) {
        for (String path : localFiles) {
            if (remote.directories().contains(path)) {
                throw new IllegalStateException("本地文件与百度网盘目录路径冲突，请先手动处理：" + path);
            }
        }
        for (String path : remote.files().keySet()) {
            if (localDirectories.contains(path)) {
                throw new IllegalStateException("百度网盘文件与本地目录路径冲突，请先手动处理：" + path);
            }
        }
    }

    private void cleanRemoteStaging(String accessToken,
                                    String remoteRoot) {
        String stagingPath = remoteRoot + "/" + STAGING_DIRECTORY;
        boolean exists = panClient.listDirectory(accessToken, remoteRoot).stream()
                .anyMatch(item -> item.directory() && stagingPath.equals(item.path()));
        if (!exists) {
            return;
        }
        List<BaiduPanEntry> stagedItems = panClient.listDirectory(accessToken, stagingPath);
        for (BaiduPanEntry item : stagedItems) {
            String filename = item.path().substring(item.path().lastIndexOf('/') + 1);
            if (item.directory() || !filename.matches("upload-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")) {
                throw new IllegalStateException("百度网盘同步保留目录包含未知文件，请先手动处理：" + item.path());
            }
        }
        List<String> stagedPaths = stagedItems.stream().map(BaiduPanEntry::path).toList();
        for (int start = 0; start < stagedPaths.size(); start += BaiduPanClient.FILE_MANAGER_BATCH_SIZE) {
            List<String> batch = stagedPaths.subList(start,
                    Math.min(start + BaiduPanClient.FILE_MANAGER_BATCH_SIZE, stagedPaths.size()));
            List<BaiduPanClient.FileManagerFailure> failures = panClient.deleteBatch(accessToken, batch);
            if (!failures.isEmpty()) {
                BaiduPanClient.FileManagerFailure failure = failures.get(0);
                throw new IllegalStateException("清理百度网盘同步临时文件失败："
                        + failure.exception().getMessage(), failure.exception());
            }
        }
        panClient.delete(accessToken, stagingPath);
    }

    private void syncDirectories(String accessToken,
                                 Path localRoot,
                                 String remoteRoot,
                                 Set<String> localFiles,
                                 Set<String> remoteDirsBefore,
                                 Set<String> baseline) throws IOException {
        Set<String> localDirs = scanLocalDirectories(localRoot, pluginDirectory);
        for (String directory : remoteDirsBefore) {
            if (!localDirs.contains(directory) && !baseline.contains(directory)) {
                Files.createDirectories(resolveLocal(localRoot, directory));
            }
        }
        for (String directory : localDirs) {
            if (!remoteDirsBefore.contains(directory) && !baseline.contains(directory)) {
                createRemoteDirectory(accessToken, remoteRoot, directory);
            }
        }

        Set<String> remoteDirs = scanRemote(accessToken, remoteRoot).directories();
        Set<String> allDirs = new LinkedHashSet<>(localDirs);
        allDirs.addAll(remoteDirs);
        allDirs.addAll(baseline);
        List<String> ordered = new ArrayList<>(allDirs);
        ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String directory : ordered) {
            boolean localExists = Files.isDirectory(resolveLocal(localRoot, directory));
            boolean remoteExists = remoteDirs.contains(directory);
            if (localExists && remoteExists) {
                continue;
            }
            if (!baseline.contains(directory)) {
                if (localExists) {
                    createRemoteDirectory(accessToken, remoteRoot, directory);
                } else if (remoteExists) {
                    Files.createDirectories(resolveLocal(localRoot, directory));
                }
            } else if (!localExists && remoteExists) {
                panClient.delete(accessToken, remoteRoot + "/" + directory);
            } else if (localExists && !remoteExists
                    && !localFiles.stream().anyMatch(path -> path.equals(directory) || path.startsWith(directory + "/"))) {
                Files.deleteIfExists(resolveLocal(localRoot, directory));
            }
        }
    }

    private void ensureRemoteRoot(String accessToken,
                                  String remoteRoot) {
        createRemoteDirectory(accessToken, "/apps", remoteRoot.substring("/apps/".length()));
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

    private String remoteRoot(String appName) {
        String name = appName.trim();
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IllegalStateException("请填写有效的百度应用名称");
        }
        return "/apps/" + name + "/notes";
    }

    private void validateSettings(BaiduSyncSettings settings) {
        if (settings.refreshToken().isBlank() && settings.accessToken().isBlank()) {
            throw new IllegalStateException("请先在插件设置中完成百度网盘授权");
        }
        if (settings.appName().isBlank()) {
            throw new IllegalStateException("请先填写百度应用名称");
        }
    }

    private void ensureSyncIdle() {
        if (status.get().running()) {
            throw new IllegalStateException("同步进行中，请稍后重试");
        }
    }

    private String relative(Path root,
                            Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String remoteRelative(String remoteRoot,
                                 String path) {
        String prefix = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : "";
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

    private String fileMd5(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 运行环境不支持 MD5", e);
        }
    }

    private String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? "同步失败" : value;
    }

    private static final class SyncCounters {
        private int uploaded;
        private int downloaded;
        private int deleted;
        private int conflicts;
    }

    private static final class UploadDispatch {
        private final String accessToken;
        private final String remoteRoot;
        private final List<FileChange> changes;
        private final UploadPlan[] plans;
        private final CompletableFuture<List<UploadPlan>> completion = new CompletableFuture<>();
        private int nextIndex;
        private int inFlight;
        private Throwable failure;

        private UploadDispatch(String accessToken, String remoteRoot, List<FileChange> changes) {
            this.accessToken = accessToken;
            this.remoteRoot = remoteRoot;
            this.changes = changes;
            this.plans = new UploadPlan[changes.size()];
        }
    }

    private record UploadPlan(BaiduPanClient.FileManagerMove move) {
    }

    private record LocalItem(Path file,
                             String relative,
                             String md5,
                             long modifiedAt,
                             long size) {
        private String parent() {
            int slash = relative.lastIndexOf('/');
            return slash < 0 ? "" : relative.substring(0, slash);
        }
    }

    private record RemoteSnapshot(Map<String, BaiduPanEntry> files,
                                  Set<String> directories) {
    }

    private enum FileAction {
        UPLOAD,
        DOWNLOAD,
        DELETE_LOCAL,
        DELETE_REMOTE
    }

    private record FileChange(String relative,
                              FileAction action,
                              LocalItem local,
                              BaiduPanEntry remote,
                              boolean overwrite,
                              boolean conflict) {
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
