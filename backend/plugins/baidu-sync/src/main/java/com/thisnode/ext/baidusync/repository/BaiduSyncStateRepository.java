package com.thisnode.ext.baidusync.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.baidusync.model.BaiduSyncState;
import com.thisnode.plugin.api.util.FileHelper;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Repository
public class BaiduSyncStateRepository {

    private final Path file;
    private final FileHelper fileHelper;

    public BaiduSyncStateRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                                    FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.file = PluginDataPathUtils.pluginDataDirectory(Path.of(dataRoot), "baidu-sync", fileHelper)
                .resolve("sync-state.json");
    }

    public synchronized BaiduSyncState get(String root,
                                           String remoteRoot) {
        try {
            BaiduSyncState state = read();
            if (state == null || state.notesRoot() == null
                    || !Path.of(state.notesRoot()).equals(Path.of(root))
                    || !remoteRoot.equals(state.remoteRoot())) {
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

    private BaiduSyncState read() throws IOException {
        if (!Files.isRegularFile(file)) return null;
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return json.isBlank() ? null : JSON.parseObject(json, BaiduSyncState.class);
    }
}
