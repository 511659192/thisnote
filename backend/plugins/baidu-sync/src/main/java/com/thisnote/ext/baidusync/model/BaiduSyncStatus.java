package com.thisnote.ext.baidusync.model;

public record BaiduSyncStatus(boolean running,
                              int uploaded,
                              int downloaded,
                              int deleted,
                              int conflicts,
                              String error) {}
