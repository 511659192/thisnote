package com.thisnote.ext.baidusync.service;

import com.thisnote.ext.baidusync.model.BaiduSyncProgressItem;
import com.thisnote.ext.baidusync.service.BaiduSyncPlanner.FileChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SyncCounters {
    int uploaded;
    int downloaded;
    int deleted;
    int conflicts;
    private final Map<String, BaiduSyncProgressItem> items = new LinkedHashMap<>();
    private Runnable progressListener = () -> { };
    private String failureMessage;

    void onProgress(Runnable listener) {
        progressListener = listener == null ? () -> { } : listener;
    }

    synchronized void initialize(List<FileChange> changes) {
        items.clear();
        for (FileChange change : changes) {
            items.put(key(change), item(change, "pending", null));
        }
        progressListener.run();
    }

    synchronized void markRunning(FileChange change) {
        items.put(key(change), item(change, "running", null));
        progressListener.run();
    }

    synchronized void markCompleted(FileChange change) {
        items.put(key(change), item(change, "done", null));
        progressListener.run();
    }

    synchronized void markFailed(FileChange change,
                                 Throwable error) {
        String message = message(error);
        if (failureMessage == null) {
            failureMessage = message;
        }
        items.put(key(change), item(change, "failed", message));
        progressListener.run();
    }

    synchronized List<BaiduSyncProgressItem> items() {
        return new ArrayList<>(items.values());
    }

    synchronized String failureMessage() {
        return failureMessage;
    }

    private static String key(FileChange change) {
        return change.previewAction() + ":" + change.relative();
    }

    private static BaiduSyncProgressItem item(FileChange change,
                                              String state,
                                              String error) {
        return new BaiduSyncProgressItem(
                change.relative(), change.previewAction(), change.conflict(), state, error);
    }

    private static String message(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "同步失败";
        }
        return error.getMessage();
    }
}
