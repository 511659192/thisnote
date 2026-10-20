package com.thisnote.ext.baidusync.model;

public record BaiduSyncProgressItem(String path,
                                    String action,
                                    boolean conflict,
                                    String state,
                                    String error) {
}
