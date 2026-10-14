package com.thisnode.ext.baidusync.repository;

import com.thisnode.ext.baidusync.model.BaiduSyncState;
import com.thisnode.plugin.api.util.JsonFileUtils;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

@Repository
public class BaiduSyncStateRepository {

    private final Path file;

    public BaiduSyncStateRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.file = PluginDataPathUtils.pluginDataDirectory(Path.of(dataRoot), "baidu-sync")
                .resolve("sync-state.json");
    }

    public synchronized BaiduSyncState get(String root,
                                           String remoteRoot) {
        try {
            BaiduSyncState state = JsonFileUtils.read(file, BaiduSyncState.class);
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
        JsonFileUtils.write(file, state);
    }
}
