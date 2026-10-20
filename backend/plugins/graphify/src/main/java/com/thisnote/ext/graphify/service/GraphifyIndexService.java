package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.GraphifyPlugin;
import com.thisnote.ext.graphify.client.GraphifyProcessRunner;
import com.thisnote.ext.graphify.repository.GraphifyStateRepository;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.event.NoteListFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import com.thisnote.plugin.api.util.FileHelper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GraphifyIndexService {

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final FileHelper fileHelper;
    private final GraphifySettingsService settingsService;
    private final GraphifyStateRepository stateRepository;
    private final GraphifyProcessRunner processRunner;
    private final GraphifyCorpusProjector corpusProjector;
    private final GraphifyPipelineRunner pipelineRunner;
    private final Path pluginDir;
    private final Path corpusDir;
    private final Path graphifyOutDir;
    private final Path graphJsonFile;
    private final Path graphHtmlFile;
    private final ScheduledExecutorService worker;
    private final GraphifyChangeQueue changeQueue;

    private volatile GraphifyStateRepository.IndexState indexState = GraphifyStateRepository.IndexState.empty();
    private volatile Status currentStatus = new Status(false, "等待首次索引", 0, 0, 0, 0,
            false, false, false, null, List.of(), null);
    private volatile boolean stopped;
    private String stateLoadError;
    private boolean indexQueued;
    private boolean relabelQueued;
    private volatile boolean resetting;
    private ApplicationEventPublisher eventPublisher;

    public GraphifyIndexService(FileHelper fileHelper,
                                GraphifySettingsService settingsService,
                                GraphifyStateRepository stateRepository,
                                GraphifyProcessRunner processRunner,
                                @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        this.settingsService = settingsService;
        this.stateRepository = stateRepository;
        this.processRunner = processRunner;
        Path notesRoot = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes");
        this.pluginDir = notesRoot.resolve("plugins").resolve(GraphifyPlugin.PLUGIN_ID);
        this.corpusDir = pluginDir.resolve("corpus");
        this.graphifyOutDir = corpusDir.resolve("graphify-out");
        this.graphJsonFile = graphifyOutDir.resolve("graph.json");
        this.graphHtmlFile = graphifyOutDir.resolve("graph.html");
        this.corpusProjector = new GraphifyCorpusProjector(fileHelper, corpusDir, graphifyOutDir);
        this.pipelineRunner = new GraphifyPipelineRunner(
                fileHelper, settingsService, processRunner, corpusDir, graphifyOutDir, graphJsonFile, graphHtmlFile
        );
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "thisnote-graphify-index");
            thread.setDaemon(true);
            return thread;
        });
        this.changeQueue = new GraphifyChangeQueue(
                worker,
                this::processQueuedChanges,
                this::markChangesQueued,
                this::shouldObserveChanges
        );
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    private List<NoteDTO> allNotes() {
        CompletableFuture<List<NoteDTO>> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteListFutureEvent(result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private NoteDTO note(String noteId) {
        NoteCardDTO content = noteContent(noteId);
        return content == null ? null : content.note();
    }

    private List<CardDTO> cards(String noteId) {
        NoteCardDTO content = noteContent(noteId);
        return content == null ? List.of() : content.cards();
    }

    private byte[] attachment(String fileName) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher().publishEvent(new AttachmentBytesFutureEvent(fileName, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private NoteCardDTO noteContent(String noteId) {
        CompletableFuture<NoteCardDTO> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteContentFutureEvent(noteId, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("Notes 消息管道尚未就绪");
        }
        return eventPublisher;
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(corpusDir);
            indexState = stateRepository.getIndexState();
            if (indexState.initialized()
                    && indexState.noteIds().isEmpty()) {
                resetEmptyGraph("启动时发现空语料，历史图谱已清空");
            }
            currentStatus = statusFor("就绪", false, indexState.initialized() ? 100 : 0,
                    0, 0, List.of(), null);
            log.info("Graphify 索引服务已初始化：indexed={}, notes={}, graphAvailable={}",
                    indexState.initialized(), indexState.noteIds().size(), Files.isRegularFile(graphJsonFile));
        } catch (Exception e) {
            stateLoadError = "索引状态无法读取：" + e.getMessage();
            currentStatus = statusFor("状态错误", false, 0, 0, 0, List.of(), stateLoadError);
            log.error("Graphify 插件状态初始化失败", e);
        }
    }

    public Status status() {
        Status current = currentStatus;
        return statusFor(current.stage(), current.running(), current.progress(),
                current.completedNotes(), current.totalNotes(), current.warnings(), current.error());
    }

    public void requestIndex(boolean force) {
        synchronized (this) {
            ensureRunning();
            if (indexQueued) {
                log.debug("Graphify 全量索引请求已忽略：已有索引任务排队");
                return;
            }
            indexQueued = true;
            log.info("Graphify 全量索引已排队：force={}, indexed={}", force, indexState.initialized());
            if (!currentStatus.running()) {
                currentStatus = statusFor("索引已排队", false, currentStatus.progress(),
                        currentStatus.completedNotes(), currentStatus.totalNotes(),
                        currentStatus.warnings(), currentStatus.error());
            }
            worker.execute(() -> {
                try {
                    indexAll(force);
                } finally {
                    synchronized (this) {
                        indexQueued = false;
                        changeQueue.scheduleIfReady();
                    }
                }
            });
        }
    }

    public void requestRelabel() {
        synchronized (this) {
            ensureRunning();
            if (relabelQueued) {
                log.debug("Graphify 社区命名请求已忽略：已有命名任务排队");
                return;
            }
            relabelQueued = true;
            log.info("Graphify 社区命名已排队");
            worker.execute(() -> {
                try {
                    relabelCommunities();
                } finally {
                    synchronized (this) {
                        relabelQueued = false;
                        changeQueue.scheduleIfReady();
                    }
                }
            });
        }
    }

    public void stop() {
        stopped = true;
        GraphifyChangeQueue.QueueSnapshot pending = changeQueue.stop();
        log.info("Graphify 索引服务正在停止：queuedNotes={}, queuedDeletes={}, unresolvedCards={}",
                pending.noteIds().size(), pending.deletedNoteIds().size(), pending.unresolvedCardIds().size());
        worker.shutdownNow();
        processRunner.stop();
    }

    public CompletableFuture<Void> resetState() {
        synchronized (this) {
            if (stopped) {
                return CompletableFuture.failedFuture(new IllegalStateException("Graphify 索引线程已停止"));
            }
            resetting = true;
            changeQueue.clear();
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                Exception failure = null;
                try {
                    clearGeneratedContent();
                    stateRepository.deleteState();
                    indexState = GraphifyStateRepository.IndexState.empty();
                    currentStatus = statusFor("等待首次索引", false, 0, 0, 0, List.of(), null);
                    log.info("Graphify 状态已重置");
                } catch (Exception e) {
                    failure = e;
                    currentStatus = statusFor("重置失败", false, currentStatus.progress(),
                            currentStatus.completedNotes(), currentStatus.totalNotes(),
                            currentStatus.warnings(), safeMessage(e));
                } finally {
                    resetting = false;
                }
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Exception e) {
            resetting = false;
            result.completeExceptionally(e);
        }
        return result;
    }

    private void indexAll(boolean force) {
        long startedAt = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        log.info("Graphify 全量索引开始：force={}, previouslyIndexed={}", force, indexState.initialized());
        try {
            if (stateLoadError != null) {
                warnings.add(stateLoadError);
            }
            currentStatus = statusFor("读取笔记", true, 2, 0, 0, warnings, null);
            List<NoteDTO> notes = allNotes();
            log.info("Graphify 笔记读取完成：notes={}", notes.size());
            if (notes.isEmpty()) {
                resetEmptyGraph("没有笔记，图谱已清空");
                log.info("Graphify 全量索引完成：空语料，elapsedMs={}",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            }

            GraphifyStateRepository.IndexState previous = indexState;
            Map<String, String> cardToNote = new LinkedHashMap<>();
            Map<String, Set<String>> attachmentToNotes = new LinkedHashMap<>();
            Set<String> noteIds = new LinkedHashSet<>();
            currentStatus = statusFor("生成笔记投影", true, 5, 0, notes.size(), warnings, null);
            int completed = 0;
            for (NoteDTO note : notes) {
                if (note == null || note.noteId() == null || note.noteId().isBlank()) {
                    continue;
                }
                noteIds.add(note.noteId());
                GraphifyCorpusProjector.ProjectionMetadata metadata = corpusProjector.project(note, cards(note.noteId()), warnings, this::attachment);
                metadata.cardIds().forEach(cardId -> cardToNote.put(cardId, note.noteId()));
                metadata.attachmentNames().forEach(fileName ->
                        attachmentToNotes.computeIfAbsent(fileName, ignored -> new LinkedHashSet<>()).add(note.noteId()));
                completed++;
                int progress = 5 + Math.round(70f * completed / Math.max(1, notes.size()));
                currentStatus = statusFor("生成笔记投影", true, progress, completed, notes.size(), warnings, null);
            }
            log.info("Graphify 笔记投影完成：notes={}, cards={}, attachments={}, elapsedMs={}",
                    completed, cardToNote.size(), attachmentToNotes.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

            for (String staleNoteId : previous.noteIds()) {
                if (!noteIds.contains(staleNoteId)) {
                    corpusProjector.remove(staleNoteId);
                }
            }
            Map<String, List<String>> attachmentMap = new LinkedHashMap<>();
            attachmentToNotes.forEach((fileName, ids) -> attachmentMap.put(fileName, List.copyOf(ids)));
            GraphifyStateRepository.IndexState projected = new GraphifyStateRepository.IndexState(
                    previous.initialized(), previous.lastCompletedAt(), noteIds, cardToNote, attachmentMap);
            stateRepository.updateIndexState(projected);
            indexState = projected;
            if (force) {
                warnings.add("强制重新抽取会绕过 Graphify 语义缓存，可能重新调用模型并产生费用");
            }
            GraphifySettingsService.Settings settings = settingsService.settings();
            String apiKey = pipelineRunner.requireApiKey(settings);
            currentStatus = statusFor("Graphify 抽取", true, 80, completed, notes.size(), warnings, null);
            pipelineRunner.run(settings, apiKey, force, !previous.initialized());

            String completedAt = Instant.now().toString();
            GraphifyStateRepository.IndexState successful = new GraphifyStateRepository.IndexState(
                    true, completedAt, noteIds, cardToNote, attachmentMap);
            stateRepository.updateIndexState(successful);
            indexState = successful;
            if (!Files.isRegularFile(graphHtmlFile)) {
                warnings.add("Graphify 未生成 graph.html；图数据仍可从 graph.json 读取");
            }
            currentStatus = statusFor("就绪", false, 100, completed, notes.size(), warnings, null);
            log.info("Graphify 全量索引完成：notes={}, cards={}, attachments={}, warnings={}, elapsedMs={}",
                    completed, cardToNote.size(), attachmentMap.size(), warnings.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (Exception e) {
            String failedStage = currentStatus.stage();
            currentStatus = statusFor("索引失败", false, currentStatus.progress(),
                    currentStatus.completedNotes(), currentStatus.totalNotes(), warnings, safeMessage(e));
            log.error("Graphify 全量索引失败：stage={}, elapsedMs={}", failedStage,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e);
        }
    }

    private void processQueuedChanges() {
        long startedAt = System.nanoTime();
        GraphifyChangeQueue.QueueSnapshot snapshot = changeQueue.snapshotAndClear();
        if (snapshot.isEmpty()) {
            return;
        }
        log.info("Graphify 增量批次开始：noteChanges={}, deletions={}, unresolvedCards={}",
                snapshot.noteIds().size(), snapshot.deletedNoteIds().size(), snapshot.unresolvedCardIds().size());
        if (!indexState.initialized()) {
            log.debug("Graphify 增量批次暂缓：首次索引尚未完成");
            restoreQueue(snapshot);
            return;
        }

        List<String> warnings = new ArrayList<>();
        Set<String> noteIds = new LinkedHashSet<>(indexState.noteIds());
        Map<String, String> cardToNote = new LinkedHashMap<>(indexState.cardToNote());
        Map<String, Set<String>> attachmentToNotes = copyAttachmentMap(indexState.attachmentToNotes());
        try {
            if (!snapshot.unresolvedCardIds().isEmpty()) {
                cardToNote.putAll(rebuildCardToNoteMapping());
                for (String cardId : snapshot.unresolvedCardIds()) {
                    String foundNoteId = cardToNote.get(cardId);
                    if (foundNoteId != null) {
                        snapshot.noteIds().add(foundNoteId);
                    }
                }
            }

            for (String deletedNoteId : snapshot.deletedNoteIds()) {
                corpusProjector.remove(deletedNoteId);
                noteIds.remove(deletedNoteId);
                removeNoteMappings(deletedNoteId, cardToNote, attachmentToNotes);
            }

            List<String> changed = snapshot.noteIds().stream()
                    .filter(noteId -> !snapshot.deletedNoteIds().contains(noteId))
                    .toList();
            if (changed.isEmpty() && snapshot.deletedNoteIds().isEmpty()) {
                indexState = new GraphifyStateRepository.IndexState(true, Instant.now().toString(),
                        noteIds, cardToNote, toAttachmentListMap(attachmentToNotes));
                stateRepository.updateIndexState(indexState);
                currentStatus = statusFor("就绪", false, 100, 0, 0, warnings, null);
                log.info("Graphify 增量批次无需更新图谱：elapsedMs={}",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            }

            currentStatus = statusFor("同步变更笔记", true, 15, 0, changed.size(), warnings, null);
            int completed = 0;
            boolean projectionChanged = false;
            for (String noteId : changed) {
                NoteDTO note = note(noteId);
                if (note == null) {
                    corpusProjector.remove(noteId);
                    noteIds.remove(noteId);
                    removeNoteMappings(noteId, cardToNote, attachmentToNotes);
                    projectionChanged = true;
                } else {
                    GraphifyCorpusProjector.ProjectionMetadata metadata = corpusProjector.project(note, cards(note.noteId()), warnings, this::attachment);
                    projectionChanged |= metadata.changed();
                    noteIds.add(noteId);
                    removeNoteMappings(noteId, cardToNote, attachmentToNotes);
                    metadata.cardIds().forEach(cardId -> cardToNote.put(cardId, noteId));
                    metadata.attachmentNames().forEach(fileName ->
                            attachmentToNotes.computeIfAbsent(fileName, ignored -> new LinkedHashSet<>()).add(noteId));
                }
                completed++;
                int progress = 15 + Math.round(60f * completed / Math.max(1, changed.size()));
                currentStatus = statusFor("同步变更笔记", true, progress, completed, changed.size(), warnings, null);
            }

            GraphifyStateRepository.IndexState projected = new GraphifyStateRepository.IndexState(
                    true, indexState.lastCompletedAt(), noteIds, cardToNote,
                    toAttachmentListMap(attachmentToNotes));
            stateRepository.updateIndexState(projected);
            indexState = projected;
            if (!projectionChanged && snapshot.deletedNoteIds().isEmpty()) {
                currentStatus = statusFor("就绪", false, 100, completed, changed.size(), warnings, null);
                log.info("Graphify 增量批次未改变投影，跳过图谱更新：notes={}, elapsedMs={}",
                        changed.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            }
            if (noteIds.isEmpty()) {
                resetEmptyGraph("笔记已全部删除，图谱已清空");
                log.info("Graphify 增量批次完成：空语料，elapsedMs={}",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return;
            }
            GraphifySettingsService.Settings settings = settingsService.settings();
            String apiKey = pipelineRunner.requireApiKey(settings);
            currentStatus = statusFor("Graphify 更新", true, 80, completed, changed.size(), warnings, null);
            pipelineRunner.run(settings, apiKey, false, false);
            indexState = new GraphifyStateRepository.IndexState(true, Instant.now().toString(),
                    noteIds, cardToNote, toAttachmentListMap(attachmentToNotes));
            stateRepository.updateIndexState(indexState);
            currentStatus = statusFor("就绪", false, 100, completed, changed.size(), warnings, null);
            log.info("Graphify 增量同步完成：updated={}, deleted={}, cards={}, attachments={}, warnings={}, elapsedMs={}",
                    changed.size(), snapshot.deletedNoteIds().size(), cardToNote.size(), attachmentToNotes.size(),
                    warnings.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (Exception e) {
            restoreQueue(snapshot);
            String failedStage = currentStatus.stage();
            currentStatus = statusFor("增量同步失败", false, currentStatus.progress(),
                    currentStatus.completedNotes(), currentStatus.totalNotes(), warnings, safeMessage(e));
            log.error("Graphify 增量同步失败：stage={}, elapsedMs={}", failedStage,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e);
        }
    }

    private void relabelCommunities() {
        long startedAt = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        try {
            GraphifySettingsService.Settings settings = settingsService.settings();
            String apiKey = pipelineRunner.requireApiKey(settings);
            if (!Files.isRegularFile(graphJsonFile)) {
                throw new IllegalStateException("尚无图谱；请先完成首次索引");
            }
            currentStatus = statusFor("重新命名社区", true, 85, 0, 0, warnings, null);
            pipelineRunner.relabel(settings, apiKey);
            GraphifyStateRepository.IndexState current = indexState;
            GraphifyStateRepository.IndexState updated = new GraphifyStateRepository.IndexState(current.initialized(),
                    Instant.now().toString(),
                    current.noteIds(), current.cardToNote(), current.attachmentToNotes());
            stateRepository.updateIndexState(updated);
            indexState = updated;
            currentStatus = statusFor("就绪", false, 100, 0, 0, warnings, null);
            log.info("Graphify 社区命名完成：elapsedMs={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (Exception e) {
            String failedStage = currentStatus.stage();
            currentStatus = statusFor("社区命名失败", false, currentStatus.progress(),
                    currentStatus.completedNotes(), currentStatus.totalNotes(), warnings, safeMessage(e));
            log.error("Graphify 社区命名失败：stage={}, elapsedMs={}", failedStage,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e);
        }
    }

    private Map<String, String> rebuildCardToNoteMapping() {
        Map<String, String> result = new LinkedHashMap<>();
        for (NoteDTO note : allNotes()) {
            if (note == null || note.noteId() == null) {
                continue;
            }
            List<CardDTO> cards = cards(note.noteId());
            if (cards == null) {
                continue;
            }
            for (CardDTO card : cards) {
                if (card != null && card.meta() != null && card.meta().cardId() != null) {
                    result.put(card.meta().cardId(), note.noteId());
                }
            }
        }
        return result;
    }

    public void queueNote(String noteId) {
        changeQueue.queueNote(noteId);
    }

    public void queueNoteDeletion(String noteId) {
        changeQueue.queueNoteDeletion(noteId);
    }

    public String noteIdForCard(String cardId) {
        return indexState.cardToNote().get(cardId);
    }

    public void queueUnresolvedCard(String cardId) {
        changeQueue.queueUnresolvedCard(cardId);
    }

    public void queueNotesForAttachments(Set<String> fileNames) {
        changeQueue.queueNotesForAttachments(fileNames,
                fileName -> indexState.attachmentToNotes().getOrDefault(fileName, List.of()));
    }

    private boolean shouldObserveChanges() {
        return !resetting && (indexState.initialized() || currentStatus.running());
    }

    private void markChangesQueued() {
        if (!currentStatus.running()) {
            currentStatus = statusFor("变更已排队", false, currentStatus.progress(),
                    currentStatus.completedNotes(), currentStatus.totalNotes(),
                    currentStatus.warnings(), currentStatus.error());
        }
    }

    private void restoreQueue(GraphifyChangeQueue.QueueSnapshot snapshot) {
        changeQueue.restore(snapshot);
    }

    private void removeNoteMappings(String noteId,
                                    Map<String, String> cardToNote,
                                    Map<String, Set<String>> attachmentToNotes) {
        cardToNote.entrySet().removeIf(entry -> entry.getValue().equals(noteId));
        attachmentToNotes.values().forEach(ids -> ids.remove(noteId));
        attachmentToNotes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static Map<String, Set<String>> copyAttachmentMap(Map<String, List<String>> source) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((fileName, noteIds) -> result.put(fileName, new LinkedHashSet<>(noteIds)));
        return result;
    }

    private static Map<String, List<String>> toAttachmentListMap(Map<String, Set<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((fileName, noteIds) -> result.put(fileName, List.copyOf(noteIds)));
        return result;
    }

    private void resetEmptyGraph(String reason) {
        int cleared = clearGeneratedContent();
        indexState = new GraphifyStateRepository.IndexState(
                true, Instant.now().toString(), Set.of(), Map.of(), Map.of());
        stateRepository.updateIndexState(indexState);
        currentStatus = statusFor("就绪", false, 100, 0, 0, List.of(), null);
        log.info("{}：clearedFiles={}", reason, cleared);
    }

    private int clearGeneratedContent() {
        int cleared = 0;
        if (Files.isDirectory(corpusDir)) {
            List<Path> paths;
            try (var stream = Files.walk(corpusDir)) {
                paths = stream.sorted(Comparator.reverseOrder()).toList();
            } catch (Exception e) {
                throw new IllegalStateException("读取 Graphify 生成目录失败", e);
            }
            for (Path path : paths) {
                if (path.equals(corpusDir)) {
                    continue;
                }
                if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    if (!fileHelper.fileDeleted(path) && Files.exists(path)) {
                        log.error("删除 Graphify 文件失败：{}", path);
                    }
                    cleared++;
                } else {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        throw new IllegalStateException("删除 Graphify 生成目录失败：" + path, e);
                    }
                }
            }
        }
        try {
            Files.createDirectories(graphifyOutDir);
        } catch (Exception e) {
            throw new IllegalStateException("重建 Graphify 输出目录失败", e);
        }
        return cleared;
    }

    private Status statusFor(String stage,
                             boolean running,
                             int progress,
                             int completedNotes,
                             int totalNotes,
                             List<String> warnings,
                             String error) {
        int queued = changeQueue.queuedCount();
        return new Status(running, stage, progress, completedNotes, totalNotes, queued,
                indexState.initialized(), Files.isRegularFile(graphJsonFile),
                Files.isRegularFile(graphHtmlFile), indexState.lastCompletedAt(),
                List.copyOf(warnings), error);
    }

    private void ensureRunning() {
        if (stopped) {
            throw new IllegalStateException("Graphify 插件正在停用");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message;
    }

    public record Status(boolean running,
                         String stage,
                         int progress,
                         int completedNotes,
                         int totalNotes,
                         int queuedNotes,
                         boolean indexed,
                         boolean graphAvailable,
                         boolean graphHtmlAvailable,
                         String lastCompletedAt,
                         List<String> warnings,
                         String error) {
    }

}
