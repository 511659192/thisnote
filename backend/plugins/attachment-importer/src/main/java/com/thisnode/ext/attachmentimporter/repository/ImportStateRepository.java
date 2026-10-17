package com.thisnode.ext.attachmentimporter.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.attachmentimporter.AttachmentImporterPlugin;
import com.thisnode.ext.attachmentimporter.model.ImportState;
import com.thisnode.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * 附件导入状态：已经见过/处理过的附件文件名。
 *
 * <p>落在 {@code <data-root>/notes/plugins/attachment-importer/imported.json}。
 * 读不出来时**不静默重建** —— 重建等于把历史附件全部当成新文件，会重复生成笔记。
 */
@Repository
public class ImportStateRepository {

    private final Path file;
    private final FileHelper fileHelper;

    public ImportStateRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                                 FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve(AttachmentImporterPlugin.PLUGIN_ID).resolve("imported.json");
    }

    /**
     * @return 导入状态；从未运行过（文件不存在或为空）时返回 {@code null}
     */
    public synchronized ImportState load() {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("读取附件导入状态失败 " + file, e);
        }
        if (json.isBlank()) {
            return null;
        }
        try {
            ImportState state = JSON.parseObject(json, ImportState.class);
            return state == null || state.getFiles() == null ? null : state;
        } catch (RuntimeException e) {
            throw new IllegalStateException("附件导入状态无法解析（已保留原文件）：" + file, e);
        }
    }

    public synchronized void save(ImportState state) {
        fileHelper.fileWritten(file, state);
    }
}
