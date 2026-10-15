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
                                           String pluginId) {
        return pluginDataDirectory(dataRoot, pluginId, null);
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
        try {
            if (!Files.isRegularFile(SETTINGS_FILE)) {
                return defaultRoot.toAbsolutePath().normalize();
            }
            String json = Files.readString(SETTINGS_FILE, StandardCharsets.UTF_8);
            Map<String, Object> settings = json.isBlank() ? null : JSON.parseObject(json, SETTINGS_TYPE);
            Object value = settings == null ? null : settings.get("notesRoot");
            if (value == null || value.toString().isBlank()) {
                return defaultRoot.toAbsolutePath().normalize();
            }
            Path root = Path.of(value.toString().trim());
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("笔记根目录必须是绝对路径");
            }
            return root.normalize();
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
            if (fileHelper != null) {
                fileHelper.fileMoved(source, target);
            } else {
                Files.move(source, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("迁移插件数据失败 " + source + " -> " + target, e);
        }
    }
}
