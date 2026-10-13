package com.thisnode.ext.favorite;

import com.thisnode.plugin.api.util.PropertiesFileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Favorite state owned by the favorite plugin.
 */
@Repository
public class FavoriteRepository {

    private static final Logger log = LoggerFactory.getLogger(FavoriteRepository.class);

    private final Path file;
    private final Object lock = new Object();

    public FavoriteRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.file = Paths.get(dataRoot, "plugins", "favorite", "favorites.properties");
    }

    public boolean isFavorite(String noteId) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));
        synchronized (lock) {
            return Boolean.parseBoolean(read().getProperty(noteId));
        }
    }

    public List<String> favoriteNoteIds() {
        synchronized (lock) {
            Properties properties = read();
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
            Properties properties = read();
            boolean current = Boolean.parseBoolean(properties.getProperty(noteId));
            if (favorite == current && (favorite || !properties.containsKey(noteId))) {
                return;
            }
            if (favorite) {
                properties.setProperty(noteId, "true");
            } else {
                properties.remove(noteId);
            }
            write(properties);
        }
    }

    public boolean removeFavorite(String noteId) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));

        synchronized (lock) {
            Properties properties = read();
            if (!properties.containsKey(noteId)) {
                return false;
            }
            properties.remove(noteId);
            write(properties);
            return true;
        }
    }

    private Properties read() {
        try {
            return PropertiesFileUtils.read(file);
        } catch (IOException | RuntimeException e) {
            log.warn("收藏文件无法读取，按空库处理：{}（{}）", file, e.toString());
            return new Properties();
        }
    }

    private void write(Properties properties) {
        try {
            PropertiesFileUtils.write(file, properties, "favorite note ids");
        } catch (IOException e) {
            throw new UncheckedIOException("收藏状态写入失败 " + file, e);
        }
    }
}
