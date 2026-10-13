package com.thisnode.ext.memorycycle.app;

import com.thisnode.plugin.api.util.PropertiesFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Card enablement state owned by the memory-cycle plugin. */
@Repository
public class EnabledCardsRepository {

    private static final Logger log = LoggerFactory.getLogger(EnabledCardsRepository.class);

    private final Path file;
    private final Object lock = new Object();

    public EnabledCardsRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.file = Paths.get(dataRoot, "plugins", "memory-cycle", "enabled-cards.properties");
    }

    public boolean isEnabled(String cardId) {
        requireCardId(cardId);
        synchronized (lock) {
            return Boolean.parseBoolean(read().getProperty(cardId));
        }
    }

    public Set<String> enabledCardIds() {
        synchronized (lock) {
            Properties properties = read();
            Set<String> cardIds = new HashSet<>();
            for (String cardId : properties.stringPropertyNames()) {
                if (Boolean.parseBoolean(properties.getProperty(cardId))) {
                    cardIds.add(cardId);
                }
            }
            return Set.copyOf(cardIds);
        }
    }

    public void setEnabled(String cardId, boolean enabled) {
        requireCardId(cardId);
        synchronized (lock) {
            Properties properties = read();
            boolean current = Boolean.parseBoolean(properties.getProperty(cardId));
            if (enabled == current && (enabled || !properties.containsKey(cardId))) {
                return;
            }
            if (enabled) {
                properties.setProperty(cardId, "true");
            } else {
                properties.remove(cardId);
            }
            write(properties);
        }
    }

    private Properties read() {
        try {
            return PropertiesFileUtils.read(file);
        } catch (IOException | RuntimeException e) {
            log.warn("记忆周期开关文件无法读取，按空库处理：{}（{}）", file, e.toString());
            return new Properties();
        }
    }

    private void write(Properties properties) {
        try {
            PropertiesFileUtils.write(file, properties, "enabled memory-cycle card ids");
        } catch (IOException e) {
            throw new UncheckedIOException("记忆周期开关写入失败 " + file, e);
        }
    }

    private static void requireCardId(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("cardId 不能为空");
        }
    }
}
