package com.thisnode.plugin.api;

import java.util.Objects;

/**
 * A persistent local file change published by a plugin.
 * {@code url} and optional {@code previousUrl} are normalized absolute {@code file:} URIs;
 * a rename is represented as {@link Operation#UPDATED} with both locations.
 */
public record FileChangeEvent(String sourcePluginId,
                              Operation operation,
                              String url,
                              String previousUrl) {

    public FileChangeEvent {
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
        Objects.requireNonNull(operation, "operation");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
    }

    public enum Operation {
        CREATED,
        DELETED,
        UPDATED
    }
}
