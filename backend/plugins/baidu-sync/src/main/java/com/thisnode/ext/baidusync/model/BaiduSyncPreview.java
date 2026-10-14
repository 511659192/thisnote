package com.thisnode.ext.baidusync.model;

import java.util.List;

public record BaiduSyncPreview(boolean remoteDirectoryExists,
                               int uploadCount,
                               int downloadCount,
                               int deleteCount,
                               int overwriteCount,
                               int conflictCount,
                               List<BaiduSyncPreviewItem> items) {
}
