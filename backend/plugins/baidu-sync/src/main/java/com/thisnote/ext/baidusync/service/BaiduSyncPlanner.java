package com.thisnote.ext.baidusync.service;

import com.thisnote.ext.baidusync.client.BaiduPanClient;
import com.thisnote.ext.baidusync.client.BaiduPanEntry;
import com.thisnote.ext.baidusync.model.BaiduSyncPreview;
import com.thisnote.ext.baidusync.model.BaiduSyncPreviewItem;
import com.thisnote.ext.baidusync.model.BaiduSyncState;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BaiduSyncPlanner {

    private static final String LEGACY_STAGING_DIRECTORY = "._thisnode_baidu_sync_staging_64fd90c1";

    private final BaiduPanClient panClient;

    public BaiduSyncPlanner(BaiduPanClient panClient) {
        this.panClient = panClient;
    }

    public LocalSnapshot scanLocal(Path notesRoot,
                                   BackupScope backupScope) throws IOException {
        if (backupScope == null) {
            throw new IllegalStateException("百度网盘备份范围尚未初始化");
        }
        Map<String, LocalItem> files = new LinkedHashMap<>();
        Set<String> directories = new LinkedHashSet<>();
        for (Path root : backupScope.scanRoots()) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                                                         BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relative = notesRoot.relativize(dir).toString().replace('\\', '/');
                    if (!backupScope.mayContain(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!relative.isBlank()) {
                        addParentDirectories(relative, directories);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs) {
                    String relative = notesRoot.relativize(file).toString().replace('\\', '/');
                    if (attrs.isRegularFile() && !Files.isSymbolicLink(file) && backupScope.includes(relative)) {
                        files.put(relative, new LocalItem(file, relative, attrs.lastModifiedTime().toMillis(), attrs.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new LocalSnapshot(files, directories);
    }

    public RemoteSnapshot scanRemote(String accessToken,
                                     String remoteRoot,
                                     BackupScope backupScope) {
        Map<String, BaiduPanEntry> files = new LinkedHashMap<>();
        Set<String> directoriesSeen = new LinkedHashSet<>();
        ArrayDeque<String> directories = new ArrayDeque<>();
        String remotePrefix = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";
        directories.add(remoteRoot);
        while (!directories.isEmpty()) {
            String directory = directories.removeFirst();
            for (BaiduPanEntry item : panClient.listDirectory(accessToken, directory)) {
                String relative = item.path().startsWith(remotePrefix) ? item.path().substring(remotePrefix.length()) : "";
                if (relative.isBlank() || !backupScope.mayContain(relative)) {
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
                } else if (backupScope.includes(relative)) {
                    files.put(relative, item);
                }
            }
        }
        return new RemoteSnapshot(files, directoriesSeen);
    }

    public List<FileChange> plan(Map<String, LocalItem> local,
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
                boolean localWins = previousFingerprint == null || conflict
                        ? localFingerprint.modifiedAtSeconds() >= remoteFingerprint.modifiedAtSeconds()
                        : localChanged || !remoteChanged;
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

    public BaiduSyncPreview preview(Map<String, LocalItem> local,
                                    RemoteSnapshot remote,
                                    List<FileChange> changes) {
        int consistent = 0;
        for (Map.Entry<String, LocalItem> entry : local.entrySet()) {
            BaiduPanEntry remoteItem = remote.files().get(entry.getKey());
            if (remoteItem != null && matchingFingerprint(entry.getValue(), remoteItem) != null) {
                consistent++;
            }
        }
        List<BaiduSyncPreviewItem> items = new ArrayList<>();
        int uploads = 0, downloads = 0, deletions = 0, overwrites = 0, conflicts = 0;
        for (FileChange change : changes) {
            if (change.action() == FileAction.UPLOAD) uploads++;
            else if (change.action() == FileAction.DOWNLOAD) downloads++;
            else deletions++;
            if (change.overwrite()) overwrites++;
            if (change.conflict()) conflicts++;
            items.add(new BaiduSyncPreviewItem(change.relative(), change.previewAction(), change.conflict()));
        }
        return new BaiduSyncPreview(true, uploads, downloads, deletions, overwrites, conflicts, consistent, items);
    }

    static FileFingerprint matchingFingerprint(LocalItem local,
                                               BaiduPanEntry remote) {
        FileFingerprint fingerprint = FileFingerprint.of(local.size(), local.modifiedAt());
        return fingerprint.equals(FileFingerprint.of(remote.size(), remote.modifiedAt())) ? fingerprint : null;
    }

    static void addParentDirectories(String relative,
                                     Set<String> directories) {
        if (relative.isBlank()) {
            return;
        }
        String current = "";
        for (String segment : relative.split("/")) {
            current = current.isEmpty() ? segment : current + "/" + segment;
            directories.add(current);
        }
    }

    public record LocalSnapshot(Map<String, LocalItem> files,
                                Set<String> directories) {
    }

    public record LocalItem(Path file,
                            String relative,
                            long modifiedAt,
                            long size) {
        public String parent() {
            int slash = relative.lastIndexOf('/');
            return slash < 0 ? "" : relative.substring(0, slash);
        }
    }

    public record RemoteSnapshot(Map<String, BaiduPanEntry> files,
                                 Set<String> directories) {
    }

    public enum FileAction {
        UPLOAD, DOWNLOAD, DELETE_LOCAL, DELETE_REMOTE
    }

    public record FileChange(String relative,
                             FileAction action,
                             LocalItem local,
                             BaiduPanEntry remote,
                             boolean overwrite,
                             boolean conflict) {
        public String previewAction() {
            return switch (action) {
                case UPLOAD -> overwrite ? "overwrite-remote" : "upload";
                case DOWNLOAD -> overwrite ? "overwrite-local" : "download";
                case DELETE_LOCAL -> "delete-local";
                case DELETE_REMOTE -> "delete-remote";
            };
        }
    }
}
