package com.thisnode.ext.baidusync.model.request;

public record UpdateSettingsRequest(String appName,
                                    String appKey,
                                    String secretKey,
                                    String signKey) {
}
