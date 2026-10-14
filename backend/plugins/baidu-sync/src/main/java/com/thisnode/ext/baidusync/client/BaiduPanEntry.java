package com.thisnode.ext.baidusync.client;

public record BaiduPanEntry(String path,
                            boolean directory,
                            long size,
                            String md5,
                            long modifiedAt,
                            long fsId) {
}
