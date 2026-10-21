package com.thisnote.ext.notes.service;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.repository.NotesSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Slf4j
@Service
public class NotesSettingsService {

    public static final int DEFAULT_FONT_SIZE = 15;
    public static final int MIN_FONT_SIZE = 10;
    public static final int MAX_FONT_SIZE = 32;

    private final NotesConfig notesConfig;
    private final NotesSettingsRepository settingsRepository;

    public NotesSettingsService(NotesConfig notesConfig,
                                NotesSettingsRepository settingsRepository) {
        this.notesConfig = notesConfig;
        this.settingsRepository = settingsRepository;
    }

    public SettingsResponse response() {
        Path root = notesConfig.getRootDir();
        return new SettingsResponse(root.toString(), root.toString(), false, currentFontSize());
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

    private int currentFontSize() {
        int fontSize = settingsRepository.getFontSize();
        return fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE ? DEFAULT_FONT_SIZE : fontSize;
    }

    public record SettingsResponse(String rootDir,
                                   String activeRootDir,
                                   boolean restartRequired,
                                   int fontSize) {
    }

    public record FontSizeUpdate(int fontSize) {
    }
}