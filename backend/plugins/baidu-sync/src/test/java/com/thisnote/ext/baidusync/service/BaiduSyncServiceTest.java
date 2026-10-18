package com.thisnote.ext.baidusync.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaiduSyncServiceTest {

    @Test
    void legacyMd5BaselineIsTreatedAsMissing() {
        assertNull(BaiduSyncService.FileFingerprint.parse("d41d8cd98f00b204e9800998ecf8427e"));
        assertEquals(new BaiduSyncService.FileFingerprint(10, 42), BaiduSyncService.FileFingerprint.parse("10:42"));
    }
}
