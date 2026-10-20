package com.thisnote.ext.baidusync.model;

import java.util.ArrayList;
import java.util.List;

public record BaiduSyncStatus(boolean running,
                              int uploaded,
                              int downloaded,
                              int deleted,
                              int conflicts,
                              String error,
                              List<BaiduSyncProgressItem> items) {

    public BaiduSyncStatus {
        items = items == null ? List.of() : new ArrayList<>(items);
    }

    public BaiduSyncStatus(boolean running,
                           int uploaded,
                           int downloaded,
                           int deleted,
                           int conflicts,
                           String error) {
        this(running, uploaded, downloaded, deleted, conflicts, error, List.of());
    }
}
