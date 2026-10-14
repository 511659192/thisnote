package com.thisnode.ext.baidusync.model;

public record BaiduSyncStatus(boolean running,
                              long startedAt,
                              long completedAt,
                              int uploaded,
                              int downloaded,
                              int deleted,
                              int conflicts,
                              String error) {

    public static BaiduSyncStatus idle() {
        return new BaiduSyncStatus(false, 0, 0, 0, 0, 0, 0, "");
    }
}
