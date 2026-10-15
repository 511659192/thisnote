package com.thisnode.ext.baidusync.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaiduSyncServiceTest {

    @Test
    void legacyMd5BaselineIsTreatedAsMissing() {
        assertNull(BaiduSyncService.parseBaselineFingerprint("d41d8cd98f00b204e9800998ecf8427e"));
        assertEquals("10:42", BaiduSyncService.parseBaselineFingerprint("10:42"));
    }
}
