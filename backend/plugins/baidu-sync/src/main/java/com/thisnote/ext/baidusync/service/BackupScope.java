package com.thisnote.ext.baidusync.service;

import com.thisnote.plugin.api.meta.PluginMetadata;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit backup scope for the platform-level Baidu backup adapter.
 */
public final class BackupScope {

    private final Path notesRoot;
    private final Set<String> roots = new LinkedHashSet<>();
    private final Map<String, List<String>> excludesByPlugin = new LinkedHashMap<>();

    public BackupScope(Path notesRoot,
                       Map<String, PluginMetadata> installedPlugins) {
        this.notesRoot = notesRoot.toAbsolutePath().normalize();
        roots.add("cards");
        roots.add("attachments");
        roots.add("plugins/notes");
        installedPlugins.forEach((pluginId, metadata) -> {
            if (pluginId == null || pluginId.isBlank()) {
                return;
            }
            String normalizedId = pluginId.trim();
            if (normalizedId.contains("/") || normalizedId.contains("\\") || normalizedId.equals("..")) {
                return;
            }
            if (!metadata.backupEnabled()) {
                return;
            }
            String pluginRoot = "plugins/" + normalizedId;
            roots.add(pluginRoot);
            List<String> excludes = metadata.backupExclude().stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> normalize(pluginRoot + "/" + value))
                    .toList();
            if (!excludes.isEmpty()) {
                excludesByPlugin.put(pluginRoot, excludes);
            }
        });
    }

    public Set<Path> scanRoots() {
        Set<Path> paths = new LinkedHashSet<>();
        for (String root : roots) {
            paths.add(notesRoot.resolve(root).normalize());
        }
        return paths;
    }

    public boolean includes(String relative) {
        String normalized = normalize(relative);
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.equals("..") || normalized.startsWith("../")) {
            return false;
        }
        boolean underRoot = roots.stream().anyMatch(root -> normalized.equals(root) || normalized.startsWith(root + "/"));
        if (!underRoot) {
            return false;
        }
        for (List<String> excludes : excludesByPlugin.values()) {
            for (String exclude : excludes) {
                if (normalized.equals(exclude) || normalized.startsWith(exclude + "/")) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean mayContain(String relativeDirectory) {
        String normalized = normalize(relativeDirectory);
        if (normalized.isEmpty()) {
            return true;
        }
        if (includes(normalized)) {
            return true;
        }
        return roots.stream().anyMatch(root -> root.startsWith(normalized + "/"));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Path normalized = Path.of(value.replace('\\', '/')).normalize();
        String text = normalized.toString().replace('\\', '/');
        return text.equals(".") ? "" : text;
    }
}
