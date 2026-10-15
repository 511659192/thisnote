package com.thisnode.plugin.api.event;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A persistent local file change published by a plugin.
 * {@code url} and optional {@code previousUrl} are normalized absolute paths;
 * a rename is represented as {@link Operation#RENAME} with both locations.
 */
public record FileChangeEvent(String sourcePluginId,
                              Operation operation,
                              Path url,
                              Path previousUrl) {

    public FileChangeEvent {
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(url, "url");
    }

    public enum Operation {
        CREATED,
        DELETED,
        UPDATED,
        RENAME
    }
}
