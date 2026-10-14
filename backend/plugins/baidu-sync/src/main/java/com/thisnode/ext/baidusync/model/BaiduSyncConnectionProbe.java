package com.thisnode.ext.baidusync.model;

public record BaiduSyncConnectionProbe(String remoteDirectory,
                                       boolean remoteDirectoryExists,
                                       int remoteFileCount,
                                       int remoteDirectoryCount) {
}
