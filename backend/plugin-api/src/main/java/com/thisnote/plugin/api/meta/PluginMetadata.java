package com.thisnote.plugin.api.meta;

import java.util.List;
import java.util.Objects;

/**
 * Static plugin metadata that must stay readable without starting the plugin container.
 */
public record PluginMetadata(String title,
                             String description,
                             boolean undeactivatable,
                             List<String> resetTargets,
                             boolean backupEnabled,
                             List<String> backupExclude) {

    public PluginMetadata {
        title = Objects.requireNonNull(title, "title");
        description = Objects.requireNonNull(description, "description");
        resetTargets = List.copyOf(Objects.requireNonNull(resetTargets, "resetTargets"));
        backupExclude = List.copyOf(Objects.requireNonNull(backupExclude, "backupExclude"));
    }
}
