package com.thisnode.plugin.api.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small standard-library helper for plugins that own a Java Properties file.
 * Each plugin chooses and owns its own file; this class only handles UTF-8 I/O.
 */
@Slf4j
public final class PropertiesFileUtils {

    private PropertiesFileUtils() {
    }

    public static Properties read(Path file) {
        Properties properties = new Properties();
        if (Files.notExists(file)) {
            log.warn("config file not exists file:{}", file);
            return properties;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            log.error("error occur where read file:{}", file, e);
        }

        return properties;
    }

    public static void write(Path file, Properties properties, String comments) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, comments);
            }
        } catch (IOException e) {
            log.error("write file error! file:{}", file);
        }
    }
}
