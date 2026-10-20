package com.thisnote.ext.baidusync.service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit backup scope for the platform-level Baidu backup adapter.
 */
public final class BackupScope {

    private final Path notesRoot;
    private final Set<String> roots = new LinkedHashSet<>();

    public BackupScope(Path notesRoot,
                       Set<String> installedPluginIds) {
        this.notesRoot = notesRoot.toAbsolutePath().normalize();
        roots.add("cards");
        roots.add("attachments");
        roots.add("plugins/notes");
        installedPluginIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(pluginId -> !pluginId.isBlank()
                        && !pluginId.contains("/")
                        && !pluginId.contains("\\")
                        && !pluginId.equals(".."))
                .forEach(pluginId -> roots.add("plugins/" + pluginId));
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
        return underRoot;
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
