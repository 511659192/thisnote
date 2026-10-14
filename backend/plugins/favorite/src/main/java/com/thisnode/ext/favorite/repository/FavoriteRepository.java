package com.thisnode.ext.favorite.repository;

import com.google.common.base.Preconditions;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import com.thisnode.plugin.api.util.PropertiesFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

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
@Slf4j
public class FavoriteRepository {

    private final Path file;
    private final Object lock = new Object();

    public FavoriteRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.file = PluginDataPathUtils.pluginDataDirectory(Paths.get(dataRoot), "favorite")
                                             .resolve("favorites.properties");
    }

    public boolean isFavorite(String noteId) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));
        synchronized (lock) {
            return Boolean.parseBoolean(PropertiesFileUtils.read(file).getProperty(noteId));
        }
    }

    public List<String> favoriteNoteIds() {
        synchronized (lock) {
            Properties properties = PropertiesFileUtils.read(file);
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
            Properties properties = PropertiesFileUtils.read(file);
            boolean current = Boolean.parseBoolean(properties.getProperty(noteId));
            if (favorite == current && (favorite || !properties.containsKey(noteId))) {
                return;
            }
            if (favorite) {
                properties.setProperty(noteId, "true");
            } else {
                properties.remove(noteId);
            }
            PropertiesFileUtils.write(file, properties, "favorite note ids");
        }
    }

    public boolean removeFavorite(String noteId) {
        Optional.ofNullable(noteId).filter(StringUtils::isNotBlank).orElseThrow(() -> new IllegalArgumentException("noteId 不能为空"));

        synchronized (lock) {
            Properties properties = PropertiesFileUtils.read(file);
            if (!properties.containsKey(noteId)) {
                return false;
            }
            properties.remove(noteId);
            PropertiesFileUtils.write(file, properties, "favorite note ids");
            return true;
        }
    }

}
