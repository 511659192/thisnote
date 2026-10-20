package com.thisnote.ext.favorite.repository;

import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.Reader;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

/**
 * Favorite state owned by the favorite plugin.
 */
@Repository
@Slf4j
public class FavoriteRepository {

    private final Path file;
    private final Object lock = new Object();
    private final FileHelper fileHelper;

    public FavoriteRepository(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot,
                              FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = Paths.get(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("favorite")
                .resolve(".favorites.properties");
    }

    public boolean isFavorite(String noteId) {
        requireNoteId(noteId);
        synchronized (lock) {
            return Boolean.parseBoolean(readProperties().getProperty(noteId));
        }
    }

    public List<String> favoriteNoteIds() {
        synchronized (lock) {
            Properties properties = readProperties();
            return properties.stringPropertyNames().stream()
                    .filter(noteId -> Boolean.parseBoolean(properties.getProperty(noteId)))
                    .sorted()
                    .toList();
        }
    }

    public void setFavorite(String noteId,
                            boolean favorite) {
        requireNoteId(noteId);
        synchronized (lock) {
            Properties properties = readProperties();
            boolean current = Boolean.parseBoolean(properties.getProperty(noteId));
            if (favorite == current && (favorite || !properties.containsKey(noteId))) {
                return;
            }
            if (favorite) {
                properties.setProperty(noteId, "true");
            } else {
                properties.remove(noteId);
            }
            writeProperties(properties);
        }
    }

    public boolean removeFavorite(String noteId) {
        requireNoteId(noteId);

        synchronized (lock) {
            Properties properties = readProperties();
            if (!properties.containsKey(noteId)) {
                return false;
            }
            properties.remove(noteId);
            writeProperties(properties);
            return true;
        }
    }

    /** 状态重置：把收藏文件重置为空。 */
    public void clear() {
        synchronized (lock) {
            writeProperties(new Properties());
        }
    }

    private Properties readProperties() {
        Properties properties = new Properties();
        if (Files.notExists(file)) return properties;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            log.error("读取收藏文件失败 file:{}", file, e);
        }
        return properties;
    }

    private void writeProperties(Properties properties) {
        StringWriter writer = new StringWriter();
        try {
            properties.store(writer, "favorite note ids");
        } catch (Exception e) {
            throw new IllegalStateException("序列化收藏文件失败", e);
        }
        fileHelper.fileWritten(file, writer.toString());
    }

    private static void requireNoteId(String noteId) {
        if (StringUtils.isBlank(noteId)) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
    }
}
