package com.thisnode.ext.notes.config;

import com.alibaba.fastjson2.TypeReference;
import com.thisnode.plugin.api.util.JsonFileUtils;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class NotesRootSettingsRepository {

    private static final Path SETTINGS_FILE = Path.of(System.getProperty("user.home"), ".thisnode", "settings.json");
    private static final TypeReference<Map<String, Object>> SETTINGS_TYPE = new TypeReference<>() {};

    public Path configuredRoot(Path defaultRoot) {
        return PluginDataPathUtils.configuredNotesRoot(defaultRoot);
    }

    public synchronized Path updateRoot(String value) {
        Path root = absolutePath(value);
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new IllegalArgumentException("笔记根目录不是文件夹：" + root);
        }
        Map<String, Object> settings = readSettings();
        settings.put("notesRoot", root.toString());
        JsonFileUtils.write(SETTINGS_FILE, settings);
        return root;
    }

    private Path absolutePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("笔记根目录不能为空");
        }
        Path root = Path.of(value.trim());
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("笔记根目录必须是绝对路径");
        }
        return root.normalize();
    }

    private Map<String, Object> readSettings() {
        try {
            Map<String, Object> settings = JsonFileUtils.read(SETTINGS_FILE, SETTINGS_TYPE);
            return settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败 " + SETTINGS_FILE, e);
        }
    }
}
