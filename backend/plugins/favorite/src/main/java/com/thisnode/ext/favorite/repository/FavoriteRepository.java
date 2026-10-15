package com.thisnode.ext.favorite.repository;

import com.thisnode.plugin.api.util.FileHelper;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    public FavoriteRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                              FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = PluginDataPathUtils.pluginDataDirectory(Paths.get(dataRoot), "favorite", fileHelper)
                .resolve("favorites.properties");
    }

    public boolean isFavorite(String noteId) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));
        synchronized (lock) {
            return Boolean.parseBoolean(readProperties().getProperty(noteId));
        }
    }

    public List<String> favoriteNoteIds() {
        synchronized (lock) {
            Properties properties = readProperties();
            List<String> ids = new ArrayList<>();
            for (String noteId : properties.stringPropertyNames()) {
                if (Boolean.parseBoolean(properties.getProperty(noteId))) {
                    ids.add(noteId);
                }
            }
            ids.sort(String::compareTo);
            return ids;
        }
    }

    public void setFavorite(String noteId,
                            boolean favorite) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));
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
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));

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

    private Properties readProperties() {
        Properties properties = new Properties();
        if (Files.notExists(file)) return properties;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.error("读取收藏文件失败 file:{}", file, e);
        }
        return properties;
    }

    private void writeProperties(Properties properties) {
        try {
            StringWriter writer = new StringWriter();
            properties.store(writer, "favorite note ids");
            fileHelper.fileWritten(file, writer.toString());
        } catch (IOException e) {
            log.error("写入收藏文件失败 file:{}", file, e);
        }
    }
}
