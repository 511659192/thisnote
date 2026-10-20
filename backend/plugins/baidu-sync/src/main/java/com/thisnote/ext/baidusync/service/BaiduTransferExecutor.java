package com.thisnote.ext.baidusync.service;

import com.thisnote.ext.baidusync.client.BaiduPanClient;
import com.thisnote.ext.baidusync.client.BaiduPanEntry;
import com.thisnote.ext.baidusync.client.BaiduPanException;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.FileChange;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
final class BaiduTransferExecutor {

    private final BaiduPanClient panClient;
    private final FileHelper fileHelper;

    BaiduTransferExecutor(BaiduPanClient panClient, FileHelper fileHelper) {
        this.panClient = panClient;
        this.fileHelper = fileHelper;
    }

    void uploadChanges(String accessToken,
                       String remoteRoot,
                       List<FileChange> changes,
                       SyncCounters counters) {
        for (FileChange change : changes) {
            log.debug("上传 {}", change.relative());
            panClient.upload(accessToken, change.local().file(), remoteRoot + "/" + change.relative());
            counters.uploaded++;
        }
    }

    void downloadChanges(String accessToken,
                         Path notesRoot,
                         List<FileChange> changes,
                         SyncCounters counters) {
        List<BaiduPanEntry> entries = changes.stream().map(FileChange::remote).toList();
        Map<Long, String> downloadUrls = panClient.downloadUrls(accessToken, entries);
        for (FileChange change : changes) {
            log.debug("下载 {}", change.relative());
            panClient.downloadWithUrl(accessToken, change.remote(),
                    resolveLocal(notesRoot, change.relative()), downloadUrls.get(change.remote().fsId()));
            counters.downloaded++;
        }
    }

    void deleteLocalChanges(List<FileChange> changes,
                            SyncCounters counters) {
        for (FileChange change : changes) {
            log.debug("删除本地 {}", change.relative());
            if (fileHelper.fileDeleted(change.local().file())) {
                counters.deleted++;
            } else if (Files.exists(change.local().file())) {
                log.error("删除本地文件失败：{}", change.local().file());
            }
        }
    }

    void deleteRemoteChanges(String accessToken,
                             List<FileChange> changes,
                             SyncCounters counters) {
        for (int start = 0; start < changes.size(); start += BaiduPanClient.FILE_MANAGER_BATCH_SIZE) {
            List<FileChange> batch = changes.subList(start,
                    Math.min(start + BaiduPanClient.FILE_MANAGER_BATCH_SIZE, changes.size()));
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
                throw new IllegalStateException("删除百度网盘文件失败：" + failure.exception().getMessage(),
                        failure.exception());
            }
        }
    }

    void createRemoteDirectory(String accessToken,
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

    void createRemoteDirectoryPath(String accessToken,
                                   String path) {
        try {
            panClient.createDirectory(accessToken, path);
        } catch (BaiduPanException e) {
            if (e.errno() != -8) {
                throw new IllegalStateException("创建百度网盘目录失败：" + path + "；" + e.getMessage(), e);
            }
        }
    }

    Path resolveLocal(Path root,
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
}
