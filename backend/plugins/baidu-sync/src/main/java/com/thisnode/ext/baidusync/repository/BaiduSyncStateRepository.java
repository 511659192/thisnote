package com.thisnode.ext.baidusync.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.baidusync.model.BaiduSyncState;
import com.thisnode.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

@Repository
public class BaiduSyncStateRepository {

    private final Path file;
    private final FileHelper fileHelper;

    public BaiduSyncStateRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                                    FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("baidu-sync").resolve("sync-state.json");
    }

    public synchronized BaiduSyncState get(String root,
                                           String remoteRoot) {
        try {
            BaiduSyncState state = read();
            if (state == null || state.notesRoot() == null || !Path.of(state.notesRoot()).equals(Path.of(root)) || !remoteRoot.equals(state.remoteRoot())) {
                return BaiduSyncState.empty(root, remoteRoot);
            }
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException("读取百度网盘同步记录失败 " + file, e);
        }
    }

    public synchronized void save(BaiduSyncState state) {
        fileHelper.fileWritten(file, state);
    }

    /** 状态重置：删除同步基线（授权凭据在另一个文件里，不受影响）。 */
    public synchronized void clear() {
        fileHelper.fileDeleted(file);
    }

    private BaiduSyncState read() throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
        return json.isBlank() ? null : JSON.parseObject(json, BaiduSyncState.class);
    }
}
