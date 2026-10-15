package com.thisnode.ext.baidusync.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaiduSyncServiceTest {

    @Test
    void fingerprintUsesSizeAndModificationTimeInWholeSeconds() {
        assertEquals("10:42", BaiduSyncService.fileFingerprint(10, 42_100));
        assertEquals("10:42", BaiduSyncService.fileFingerprint(10, 42_999));
        assertEquals("11:42", BaiduSyncService.fileFingerprint(11, 42_999));
        assertEquals("10:43", BaiduSyncService.fileFingerprint(10, 43_000));
    }

    @Test
    void legacyMd5BaselineIsTreatedAsMissing() {
        assertNull(BaiduSyncService.parseBaselineFingerprint("d41d8cd98f00b204e9800998ecf8427e"));
        assertEquals("10:42", BaiduSyncService.parseBaselineFingerprint("10:42"));
    }
}
