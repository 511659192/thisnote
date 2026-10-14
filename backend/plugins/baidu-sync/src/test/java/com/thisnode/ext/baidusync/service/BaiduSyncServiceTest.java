package com.thisnode.ext.baidusync.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduSyncServiceTest {

    @Test
    void missingBaselineDoesNotConfirmBothSidesChanged() {
        assertFalse(BaiduSyncService.isConfirmedConflict(null, true, true));
    }

    @Test
    void knownBaselineConfirmsConflictWhenBothSidesChanged() {
        assertTrue(BaiduSyncService.isConfirmedConflict("baseline", true, true));
    }
}
