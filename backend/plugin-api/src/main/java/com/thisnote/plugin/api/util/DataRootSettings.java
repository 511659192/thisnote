package com.thisnote.plugin.api.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Resolves the active data root before Spring starts and persists root changes for the next restart.
 */
public final class DataRootSettings {

    public static final String PROPERTY = "thisnote.data-root";

    private static final String ENV = "THISNOTE_DATA_ROOT";
    private static final Path SETTINGS_FILE = Path.of(System.getProperty("user.home"), ".thisnote", "settings.json");
    private static final Logger log = Logger.getLogger(DataRootSettings.class.getName());

    private DataRootSettings() {
    }

    public static void applyConfiguredDataRoot(String... args) {
        if (hasExplicitOverride(args)) {
            log.info("数据根目录使用显式启动配置，忽略本机设置文件");
            return;
        }
        Optional<Path> configured = configuredDataRoot();
        if (configured.isEmpty()) return;
        System.setProperty(PROPERTY, configured.get().toString());
        log.info("数据根目录已从本机设置加载：" + configured.get());
    }

    public static Optional<Path> configuredDataRoot() {
        if (!Files.exists(SETTINGS_FILE)) return Optional.empty();
        try {
            var raw = JSON.parseObject(Files.readString(SETTINGS_FILE, StandardCharsets.UTF_8));
            String value = raw == null ? null : raw.getString("dataRoot");
            if (value == null || value.isBlank()) return Optional.empty();
            return Optional.of(normalize(Path.of(value.trim())));
        } catch (IOException e) {
            log.severe("读取数据根配置失败：path=" + SETTINGS_FILE);
            throw new IllegalStateException("读取数据根配置失败：" + SETTINGS_FILE, e);
        } catch (RuntimeException e) {
            log.severe("解析数据根配置失败：path=" + SETTINGS_FILE + ", reason=" + e.getMessage());
            throw e;
        }
    }

    public static synchronized Path save(Path dataRoot) {
        Path normalized = normalize(dataRoot);
        Path temporary = null;
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            temporary = Files.createTempFile(SETTINGS_FILE.getParent(), "settings-", ".tmp");
            String json = JSON.toJSONString(new StoredSettings(normalized.toString()), JSONWriter.Feature.PrettyFormat);
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, SETTINGS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, SETTINGS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("数据根配置已保存：path=" + SETTINGS_FILE + ", dataRoot=" + normalized);
            return normalized;
        } catch (IOException e) {
            log.severe("保存数据根配置失败：path=" + SETTINGS_FILE + ", dataRoot=" + normalized);
            throw new IllegalStateException("保存数据根配置失败：" + SETTINGS_FILE, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.warning("清理数据根配置临时文件失败：path=" + temporary);
                }
            }
        }
    }

    public static Path normalize(Path root) {
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("数据根目录必须是绝对路径");
        }
        Path normalized = root.normalize();
        if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("数据根目录不是文件夹：" + normalized);
        }
        return normalized;
    }

    private static boolean hasExplicitOverride(String... args) {
        String systemValue = System.getProperty(PROPERTY);
        if (systemValue != null && !systemValue.isBlank()) return true;
        String environmentValue = System.getenv(ENV);
        if (environmentValue != null && !environmentValue.isBlank()) return true;
        for (String arg : args) {
            if (arg == null) continue;
            if (arg.equals("--" + PROPERTY) || arg.startsWith("--" + PROPERTY + "=")) return true;
        }
        return false;
    }

    private record StoredSettings(String dataRoot) {
    }
}
