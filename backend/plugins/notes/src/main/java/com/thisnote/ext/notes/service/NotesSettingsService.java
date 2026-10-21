package com.thisnote.ext.notes.service;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.repository.NotesSettingsRepository;
import com.thisnote.plugin.api.util.DataRootSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Slf4j
@Service
public class NotesSettingsService {

    public static final int DEFAULT_FONT_SIZE = 15;
    public static final int MIN_FONT_SIZE = 10;
    public static final int MAX_FONT_SIZE = 32;

    private final NotesConfig notesConfig;
    private final NotesSettingsRepository settingsRepository;
    private final StorageInitializationService storageInitializationService;

    public NotesSettingsService(NotesConfig notesConfig,
                                NotesSettingsRepository settingsRepository,
                                StorageInitializationService storageInitializationService) {
        this.notesConfig = notesConfig;
        this.settingsRepository = settingsRepository;
        this.storageInitializationService = storageInitializationService;
    }

    public SettingsResponse response() {
        Path activeDataRoot = notesConfig.getDataRoot();
        Path configuredDataRoot = DataRootSettings.configuredDataRoot().orElse(activeDataRoot);
        return response(configuredDataRoot, activeDataRoot);
    }

    public synchronized SettingsResponse update(FontSizeUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("笔记设置不能为空");
        }
        int fontSize = update.fontSize();
        if (fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE) {
            log.warn("笔记正文字号更新被拒绝：fontSize={}, min={}, max={}",
                    fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
            throw new IllegalArgumentException("笔记正文字号必须在 10 到 32 之间");
        }
        settingsRepository.updateFontSize(fontSize);
        log.info("笔记正文字号已更新：fontSize={}", fontSize);
        return response();
    }

    public synchronized SettingsResponse updateDataRoot(DataRootUpdate update) {
        if (update == null || update.dataRoot() == null || update.dataRoot().isBlank()) {
            throw new IllegalArgumentException("数据根目录不能为空");
        }
        Path dataRoot;
        try {
            dataRoot = DataRootSettings.normalize(Path.of(update.dataRoot().trim()));
        } catch (InvalidPathException e) {
            log.warn("数据根目录格式无效：value={}", update.dataRoot());
            throw new IllegalArgumentException("数据根目录不是有效路径：" + update.dataRoot(), e);
        } catch (RuntimeException e) {
            log.warn("数据根目录校验失败：value={}, reason={}", update.dataRoot(), e.getMessage());
            throw e;
        }

        storageInitializationService.initializeDataRoot(dataRoot);
        DataRootSettings.save(dataRoot);
        SettingsResponse response = response();
        log.info("数据根目录配置已更新：dataRoot={}, activeDataRoot={}, restartRequired={}",
                response.dataRoot(), response.activeDataRoot(), response.restartRequired());
        return response;
    }

    private SettingsResponse response(Path configuredDataRoot,
                                      Path activeDataRoot) {
        Path notesRoot = configuredDataRoot.resolve("notes");
        Path activeNotesRoot = activeDataRoot.resolve("notes");
        return new SettingsResponse(configuredDataRoot.toString(),
                activeDataRoot.toString(),
                notesRoot.toString(),
                activeNotesRoot.toString(),
                !configuredDataRoot.equals(activeDataRoot),
                currentFontSize());
    }

    private int currentFontSize() {
        int fontSize = settingsRepository.getFontSize();
        return fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE ? DEFAULT_FONT_SIZE : fontSize;
    }

    public record SettingsResponse(String dataRoot,
                                   String activeDataRoot,
                                   String notesRoot,
                                   String activeNotesRoot,
                                   boolean restartRequired,
                                   int fontSize) {
    }

    public record FontSizeUpdate(int fontSize) {
    }

    public record DataRootUpdate(String dataRoot) {
    }
}
