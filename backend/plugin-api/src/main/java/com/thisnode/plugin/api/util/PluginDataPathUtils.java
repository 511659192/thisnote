package com.thisnode.plugin.api.util;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginDataPathUtils {

    private PluginDataPathUtils() {
    }

    public static Path pluginDataDirectory(Path dataRoot,
                                           String pluginId,
                                           FileHelper fileHelper) {
        Path baseDirectory = dataRoot.toAbsolutePath().normalize();
        Path legacyDirectory = baseDirectory.resolve("plugins").resolve(pluginId);
        Path notesRoot = baseDirectory.resolve("notes");
        Path pluginDirectory = notesRoot.resolve("plugins").resolve(pluginId);
        moveLegacyDirectory(legacyDirectory, pluginDirectory, fileHelper);
        return pluginDirectory;
    }

    private static void moveLegacyDirectory(Path legacyDirectory,
                                            Path pluginDirectory,
                                            FileHelper fileHelper) {
        Path source = legacyDirectory.normalize();
        Path target = pluginDirectory.normalize();
        if (!Files.exists(source) || source.equals(target)) {
            return;
        }
        if (target.startsWith(source)) {
            throw new IllegalStateException("插件数据目录不能迁移到其自身子目录：" + target);
        }
        if (Files.exists(target)) {
            throw new IllegalStateException("插件数据迁移目标已存在，未覆盖：" + target);
        }
        try {
            Files.createDirectories(target.getParent());
            fileHelper.fileMoved(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException("迁移插件数据失败 " + source + " -> " + target, e);
        }
    }
}
