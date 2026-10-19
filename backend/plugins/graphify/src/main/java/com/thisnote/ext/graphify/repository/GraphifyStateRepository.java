package com.thisnote.ext.graphify.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class GraphifyStateRepository {

    private final FileHelper fileHelper;
    private final Path indexStateFile;
    private IndexState cachedIndexState;

    public GraphifyStateRepository(FileHelper fileHelper,
                                   @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins").resolve("graphify");
        this.indexStateFile = pluginDir.resolve("index-state.json");
    }

    public synchronized IndexState indexState() {
        if (cachedIndexState != null) {
            return cachedIndexState;
        }
        if (!Files.exists(indexStateFile)) {
            cachedIndexState = IndexState.empty();
            return cachedIndexState;
        }
        try {
            IndexState stored = JSON.parseObject(Files.readString(indexStateFile), IndexState.class);
            cachedIndexState = stored == null ? IndexState.empty() : stored;
            return cachedIndexState;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Graphify 索引状态文件无法读取", e);
        }
    }

    public synchronized void saveIndexState(IndexState state) {
        cachedIndexState = state;
        fileHelper.fileWritten(indexStateFile, state);
    }

    public synchronized void clear() {
        if (!fileHelper.fileDeleted(indexStateFile) && Files.exists(indexStateFile)) {
            throw new IllegalStateException("Graphify 索引状态文件无法删除");
        }
        cachedIndexState = IndexState.empty();
    }

    public record IndexState(boolean initialized,
                             String lastCompletedAt,
                             Set<String> noteIds,
                             Map<String, String> cardToNote,
                             Map<String, List<String>> attachmentToNotes) {

        public IndexState {
            noteIds = noteIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(noteIds));
            cardToNote = cardToNote == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(cardToNote));
            if (attachmentToNotes == null) {
                attachmentToNotes = Map.of();
            } else {
                Map<String, List<String>> copy = new LinkedHashMap<>();
                attachmentToNotes.forEach((name, ids) ->
                        copy.put(name, ids == null ? List.of() : List.copyOf(ids)));
                attachmentToNotes = Collections.unmodifiableMap(copy);
            }
        }

        public static IndexState empty() {
            return new IndexState(false, null, Set.of(), Map.of(), Map.of());
        }
    }
}
