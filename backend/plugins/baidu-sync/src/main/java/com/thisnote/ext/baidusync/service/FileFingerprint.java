package com.thisnote.ext.baidusync.service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record FileFingerprint(long size, long modifiedAtSeconds) {

    static FileFingerprint of(long size,
                              long modifiedAtMillis) {
        return new FileFingerprint(size, Math.floorDiv(modifiedAtMillis, 1000L));
    }

    /**
     * 基线持久化格式为 size:mtimeSeconds；无法解析的值视为缺失。
     */
    static FileFingerprint parse(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            return null;
        }
        try {
            long size = Long.parseLong(value.substring(0, separator));
            long modifiedAtSeconds = Long.parseLong(value.substring(separator + 1));
            return size < 0 ? null : new FileFingerprint(size, modifiedAtSeconds);
        } catch (NumberFormatException e) {
            log.error("百度网盘同步基线无法解析：{}", value, e);
            return null;
        }
    }

    String toStorage() {
        return size + ":" + modifiedAtSeconds;
    }
}
