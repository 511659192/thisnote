package com.thisnode.plugin.api.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PluginDataPathUtils {

    private static final Path SETTINGS_FILE = Path.of(System.getProperty("user.home"), ".thisnode", "settings.json");
    private static final TypeReference<Map<String, Object>> SETTINGS_TYPE = new TypeReference<>() {};

    private PluginDataPathUtils() {
    }

    public static Path pluginDataDirectory(Path dataRoot,
                                           String pluginId,
                                           FileHelper fileHelper) {
        Path baseDirectory = dataRoot.toAbsolutePath().normalize();
        Path legacyDirectory = baseDirectory.resolve("plugins").resolve(pluginId);
        Path notesRoot = configuredNotesRoot(baseDirectory.resolve("notes"));
        Path pluginDirectory = notesRoot.resolve("plugins").resolve(pluginId);
        moveLegacyDirectory(legacyDirectory, pluginDirectory, fileHelper);
        return pluginDirectory;
    }

    public static Path configuredNotesRoot(Path defaultRoot) {
        if (!Files.exists(SETTINGS_FILE)) {
            return defaultRoot;
        }
        try {
            Map<String, Object> settings = JSON.parseObject(Files.readString(SETTINGS_FILE, StandardCharsets.UTF_8), SETTINGS_TYPE);
            return Path.of(settings.get("notesRoot").toString());
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败 " + SETTINGS_FILE, e);
        }
    }

    private static void moveLegacyDirectory(Path legacyDirectory,
                                            Path pluginDirectory,
                                            FileHelper fileHelper) {
        Path source = legacyDirectory.normalize();
        Path target = pluginDirectory.normalize();
        if (!Files.isDirectory(source) || source.equals(target)) {
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
