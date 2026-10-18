package com.thisnote.ext.baidusync.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record BaiduSyncState(String notesRoot,
                             String remoteRoot,
                             long completedAt,
                             Map<String, String> files,
                             Set<String> directories) {

    public static BaiduSyncState empty(String root,
                                       String remoteRoot) {
        return new BaiduSyncState(root, remoteRoot, 0, new LinkedHashMap<>(), new LinkedHashSet<>());
    }
}
