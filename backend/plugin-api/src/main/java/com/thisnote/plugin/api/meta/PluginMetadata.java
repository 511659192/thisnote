package com.thisnote.plugin.api.meta;

import java.util.Objects;

/**
 * Static plugin metadata that must stay readable without starting the plugin container.
 */
public record PluginMetadata(String title, String description, boolean undeactivatable) {

    public PluginMetadata {
        title = Objects.requireNonNull(title, "title");
        description = Objects.requireNonNull(description, "description");
    }
}
