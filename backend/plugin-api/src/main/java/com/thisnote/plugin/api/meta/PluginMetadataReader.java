package com.thisnote.plugin.api.meta;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@code META-INF/thisnote/plugin.json} from a plugin jar or development directory.
 */
@Slf4j
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
            } catch (Exception e) {
                // Fall through to the development class directory.
                log.error("读取插件 {} 的类加载器清单失败，尝试开发目录", wrapper.getPluginId(), e);
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
            } catch (Exception e) {
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
        } catch (Exception e) {
            throw new IllegalStateException("插件 " + wrapper.getPluginId() + " 的清单无法读取", e);
        }
    }

    static PluginMetadata parse(byte[] bytes) {
        JSONObject raw;
        try {
            raw = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
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
        return new PluginMetadata(title.trim(), description.trim(), raw.getBooleanValue("undeactivatable"));
    }
}
