package com.thisnote.plugin.api.meta;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@code META-INF/thisnote/plugin.json} from a plugin jar or development directory.
 */
public final class PluginMetadataReader {

    public static final String RESOURCE_PATH = "META-INF/thisnote/plugin.json";

    private PluginMetadataReader() {
    }

    public static PluginMetadata read(PluginWrapper wrapper) {
        ClassLoader classLoader = wrapper.getPluginClassLoader();
        if (classLoader != null) {
            try (InputStream input = classLoader.getResourceAsStream(RESOURCE_PATH)) {
                if (input != null) {
                    return parse(input.readAllBytes());
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the development class directory.
            }
        }

        Path path = wrapper.getPluginPath();
        if (path == null) {
            throw new IllegalStateException("插件 " + wrapper.getPluginId() + " 缺少 " + RESOURCE_PATH);
        }
        if (Files.isDirectory(path)) {
            Path resource = path.resolve("classes").resolve(RESOURCE_PATH);
            try {
                return parse(Files.readAllBytes(resource));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("插件 " + wrapper.getPluginId() + " 的清单无法读取", e);
            }
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(RESOURCE_PATH);
            if (entry == null) {
                throw new IllegalStateException("插件 " + wrapper.getPluginId() + " 缺少 " + RESOURCE_PATH);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return parse(input.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("插件 " + wrapper.getPluginId() + " 的清单无法读取", e);
        }
    }

    static PluginMetadata parse(byte[] bytes) {
        JSONObject raw;
        try {
            raw = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("插件清单不是有效 JSON", e);
        }
        if (raw == null) {
            throw new IllegalArgumentException("插件清单为空");
        }
        String title = raw.getString("title");
        String description = raw.getString("description");
        if (!raw.containsKey("title") || title == null || title.isBlank()) {
            throw new IllegalArgumentException("插件清单缺少 title");
        }
        if (!raw.containsKey("description") || description == null || description.isBlank()) {
            throw new IllegalArgumentException("插件清单缺少 description");
        }
        if (!raw.containsKey("undeactivatable")) {
            throw new IllegalArgumentException("插件清单缺少 undeactivatable");
        }
        JSONArray resetTargets = raw.getJSONArray("resetTargets");
        JSONArray backupExclude = raw.getJSONArray("backupExclude");
        if (resetTargets == null) {
            throw new IllegalArgumentException("插件清单缺少 resetTargets");
        }
        if (!raw.containsKey("backupEnabled")) {
            throw new IllegalArgumentException("插件清单缺少 backupEnabled");
        }
        if (backupExclude == null) {
            throw new IllegalArgumentException("插件清单缺少 backupExclude");
        }
        return new PluginMetadata(title.trim(), description.trim(), raw.getBooleanValue("undeactivatable"), resetTargets.toJavaList(String.class), raw.getBooleanValue("backupEnabled"),
                                  backupExclude.toJavaList(String.class));
    }
}
