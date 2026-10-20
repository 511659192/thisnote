package com.thisnote.ext.graphify.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.GraphifyPlugin;
import com.thisnote.ext.graphify.client.GraphifyProcessRunner;
import com.thisnote.ext.graphify.repository.GraphifyStateRepository;
import com.thisnote.plugin.api.event.AttachmentBytesQueryEvent;
import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.NoteContentQueryEvent;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.event.NoteListQueryEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import com.thisnote.plugin.api.util.FileHelper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GraphifyIndexService {

    private static final long IMAGE_INLINE_LIMIT = 5L * 1024 * 1024;
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((<[^>]+>|(?:\\\\.|[^)])*)\\)");
    private static final Pattern ATTACHMENT_TARGET = Pattern.compile("(?i)(?:\\.\\./)?attachments/(.+?\\.(?:png|jpg|webp))(?:[?#].*)?$");
    private static final Pattern SAFE_IMAGE_NAME = Pattern.compile("(?i)[^/\\\\]+\\.(?:png|jpg|webp)");
    private static final Pattern PASTE_ASSET_REFERENCE = Pattern.compile("paste-[0-9a-f]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile(
            "^(?:pasted image(?: note| attachment)?|referenced image attachment\\b.*|image attachment\\s*:.*|paste note\\s*:.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HASH_ONLY = Pattern.compile(
            "^(?:[0-9a-f]{16,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$",
            Pattern.CASE_INSENSITIVE);
    private static final String GRAPHIFY_POST_MESSAGE_MARKER = "thisnote-graphify-open-note";
    private static final int RELATED_NOTES_LIMIT = 5;
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final FileHelper fileHelper;
    private final GraphifySettingsService settingsService;
    private final GraphifyStateRepository stateRepository;
    private final GraphifyProcessRunner processRunner;
    private final Path pluginDir;
    private final Path corpusDir;
    private final Path graphifyOutDir;
    private final Path graphJsonFile;
    private final Path graphHtmlFile;
    private final ScheduledExecutorService worker;
    private final Object queueLock = new Object();
    private final Set<String> queuedNotes = new LinkedHashSet<>();
    private final Set<String> deletedNotes = new LinkedHashSet<>();
    private final Set<String> unresolvedCardIds = new LinkedHashSet<>();

    private volatile GraphifyStateRepository.IndexState indexState = GraphifyStateRepository.IndexState.empty();
    private volatile Status currentStatus = new Status(false, "等待首次索引", 0, 0, 0, 0,
            false, false, false, null, List.of(), null);
    private volatile boolean stopped;
    private String stateLoadError;
    private ScheduledFuture<?> queuedChangesTask;
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
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "thisnote-graphify-index");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    private List<NoteDTO> allNotes() {
        CompletableFuture<List<NoteDTO>> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteListQueryEvent(result));
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
        publisher().publishEvent(new AttachmentBytesQueryEvent(fileName, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private NoteCardDTO noteContent(String noteId) {
        CompletableFuture<NoteCardDTO> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteContentQueryEvent(noteId, result));
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

    public JSONObject graph() {
        if (!Files.isRegularFile(graphJsonFile)) {
            return null;
        }
        try {
            return JSON.parseObject(Files.readString(graphJsonFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Graphify graph.json 无法读取", e);
        }
    }

    public String graphHtml() {
        if (!Files.isRegularFile(graphHtmlFile)) {
            return null;
        }
        try {
            String html = Files.readString(graphHtmlFile, StandardCharsets.UTF_8);
            String anchor = "network.once('stabilizationIterationsDone', () => {";
            if (!html.contains(anchor)) {
                return html;
            }
            String hook = """
                    network.on('doubleClick', params => {
                      if (!params.nodes || !params.nodes.length) return;
                      const node = nodesDS.get(params.nodes[0]);
                      if (!node || !node.source_file) return;
                      window.parent.postMessage({
                        source: '%s',
                        type: 'open-note',
                        source_file: node.source_file
                      }, '*');
                    });
                    """.formatted(GRAPHIFY_POST_MESSAGE_MARKER);
            return html.replace(anchor, hook + "\n" + anchor);
        } catch (Exception e) {
            throw new IllegalStateException("Graphify graph.html 无法读取", e);
        }
    }

    public Map<String, NoteTags> tags() {
        Map<String, Set<String>> concepts = new LinkedHashMap<>();
        Map<String, Set<String>> communities = new LinkedHashMap<>();
        JSONObject graph = graph();
        if (graph != null) {
            JSONArray nodes = graph.getJSONArray("nodes");
            if (nodes != null) {
                for (Object value : nodes) {
                    if (!(value instanceof JSONObject node)) {
                        continue;
                    }
                    String noteId = noteIdFromSource(node.getString("source_file"));
                    if (noteId == null) {
                        continue;
                    }
                    String conceptName = node.getString("label");
                    if (isMeaningfulTag(conceptName)) {
                        concepts.computeIfAbsent(noteId, ignored -> new LinkedHashSet<>()).add(conceptName.trim());
                    }
                    String communityName = node.getString("community_name");
                    if (!isMeaningfulTag(communityName)) {
                        continue;
                    }
                    communities.computeIfAbsent(noteId, ignored -> new LinkedHashSet<>()).add(communityName.trim());
                }
            }
        }

        Set<String> noteIds = new LinkedHashSet<>(indexState.noteIds());
        noteIds.addAll(concepts.keySet());
        noteIds.addAll(communities.keySet());
        Map<String, NoteTags> result = new LinkedHashMap<>();
        for (String noteId : noteIds) {
            List<String> conceptTags = concepts.getOrDefault(noteId, Set.of()).stream().sorted().toList();
            List<String> communityTags = communities.getOrDefault(noteId, Set.of()).stream().sorted().toList();
            result.put(noteId, new NoteTags(conceptTags, communityTags));
        }
        return result;
    }

    public RelatedNotes relatedNotes(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        JSONObject graph = graph();
        if (graph == null) {
            return new RelatedNotes(false, List.of());
        }

        Map<String, NoteDTO> notesById = new LinkedHashMap<>();
        for (NoteDTO note : allNotes()) {
            if (note != null && note.noteId() != null) {
                notesById.put(note.noteId(), note);
            }
        }
        if (!notesById.containsKey(noteId)) {
            return new RelatedNotes(true, List.of());
        }

        Map<String, Map<String, String>> conceptsByNote = new LinkedHashMap<>();
        Map<String, Set<String>> communitiesByNote = new LinkedHashMap<>();
        Map<String, String> nodeNotes = new HashMap<>();
        JSONArray nodes = graph.getJSONArray("nodes");
        if (nodes != null) {
            for (Object value : nodes) {
                if (!(value instanceof JSONObject node)) {
                    continue;
                }
                String ownerNoteId = noteIdFromSource(node.getString("source_file"));
                String nodeId = node.getString("id");
                if (ownerNoteId == null || nodeId == null) {
                    continue;
                }
                nodeNotes.put(nodeId, ownerNoteId);

                String label = node.getString("label");
                if (isMeaningfulTag(label)) {
                    conceptsByNote.computeIfAbsent(ownerNoteId, ignored -> new LinkedHashMap<>())
                            .putIfAbsent(normalizeLabel(label), label.trim());
                }
                String community = node.getString("community_name");
                if (isMeaningfulTag(community)) {
                    communitiesByNote.computeIfAbsent(ownerNoteId, ignored -> new LinkedHashSet<>())
                            .add(community.trim());
                }
            }
        }

        for (Map.Entry<String, NoteDTO> entry : notesById.entrySet()) {
            Map<String, String> concepts = conceptsByNote.get(entry.getKey());
            if (concepts != null) {
                concepts.remove(normalizeLabel(entry.getValue().title()));
            }
        }

        Map<String, Integer> conceptFrequency = new HashMap<>();
        for (Map<String, String> concepts : conceptsByNote.values()) {
            for (String concept : concepts.keySet()) {
                conceptFrequency.merge(concept, 1, Integer::sum);
            }
        }

        Map<String, RelatedCandidate> candidates = new LinkedHashMap<>();
        Map<String, String> currentConcepts = conceptsByNote.getOrDefault(noteId, Map.of());
        for (Map.Entry<String, Map<String, String>> entry : conceptsByNote.entrySet()) {
            String otherNoteId = entry.getKey();
            if (otherNoteId.equals(noteId)) {
                continue;
            }
            for (Map.Entry<String, String> concept : currentConcepts.entrySet()) {
                if (!entry.getValue().containsKey(concept.getKey())) {
                    continue;
                }
                RelatedCandidate candidate = candidates.computeIfAbsent(otherNoteId, RelatedCandidate::new);
                candidate.score += 6.0 / conceptFrequency.getOrDefault(concept.getKey(), 1);
                candidate.sharedConcepts.add(new ConceptMatch(concept.getKey(), concept.getValue()));
            }
        }

        JSONArray links = graph.getJSONArray("links");
        if (links != null) {
            for (Object value : links) {
                if (!(value instanceof JSONObject link)) {
                    continue;
                }
                String sourceNoteId = nodeNotes.get(endpointId(link.get("source")));
                String targetNoteId = nodeNotes.get(endpointId(link.get("target")));
                String otherNoteId;
                if (noteId.equals(sourceNoteId) && targetNoteId != null && !noteId.equals(targetNoteId)) {
                    otherNoteId = targetNoteId;
                } else if (noteId.equals(targetNoteId) && sourceNoteId != null && !noteId.equals(sourceNoteId)) {
                    otherNoteId = sourceNoteId;
                } else {
                    continue;
                }
                if (!notesById.containsKey(otherNoteId)) {
                    continue;
                }

                String relation = link.getString("relation");
                RelatedCandidate candidate = candidates.computeIfAbsent(otherNoteId, RelatedCandidate::new);
                if (candidate.relationLabels.add(relationLabel(relation))) {
                    double confidence = link.getDoubleValue("confidence_score");
                    if (confidence <= 0) {
                        confidence = 1.0;
                    }
                    candidate.score += relationWeight(relation) * (0.5 + 0.5 * Math.min(1.0, confidence));
                }
            }
        }

        Set<String> currentCommunities = communitiesByNote.getOrDefault(noteId, Set.of());
        for (RelatedCandidate candidate : candidates.values()) {
            Set<String> otherCommunities = communitiesByNote.get(candidate.noteId);
            if (otherCommunities == null) {
                continue;
            }
            for (String community : currentCommunities) {
                if (otherCommunities.contains(community)) {
                    candidate.sharedCommunities.add(community);
                }
            }
            if (!candidate.sharedCommunities.isEmpty()) {
                candidate.score += 1.0;
            }
        }

        List<RelatedNote> related = candidates.values().stream()
                .filter(candidate -> notesById.containsKey(candidate.noteId))
                .sorted(Comparator.comparingDouble((RelatedCandidate candidate) -> candidate.score).reversed()
                        .thenComparing(candidate -> noteTitle(notesById.get(candidate.noteId)),
                                String.CASE_INSENSITIVE_ORDER))
                .limit(RELATED_NOTES_LIMIT)
                .map(candidate -> new RelatedNote(
                        candidate.noteId,
                        noteTitle(notesById.get(candidate.noteId)),
                        relatedReasons(candidate, conceptFrequency)))
                .toList();
        return new RelatedNotes(true, related);
    }

    public void requestIndex(boolean force) {
        synchronized (queueLock) {
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
                    synchronized (queueLock) {
                        indexQueued = false;
                        scheduleQueuedChangesIfReady();
                    }
                }
            });
        }
    }

    public void requestRelabel() {
        synchronized (queueLock) {
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
                    synchronized (queueLock) {
                        relabelQueued = false;
                        scheduleQueuedChangesIfReady();
                    }
                }
            });
        }
    }

    public void onNoteChanged(NoteChangedEvent change) {
        if (change.kind() == NoteChangedEvent.Kind.TEXT_CHANGED) {
            return;
        }
        if (change.kind() == NoteChangedEvent.Kind.DELETED) {
            queueNoteDeletion(change.noteId());
        } else {
            queueNote(change.noteId());
        }
    }

    public void onCardChanged(CardChangedEvent change) {
        if (change.kind() == CardChangedEvent.Kind.REQUEST) {
            return;
        }
        if (!shouldObserveChanges()) {
            return;
        }
        String noteId = indexState.cardToNote().get(change.cardId());
        if (noteId != null) {
            queueNote(noteId);
            return;
        }
        synchronized (queueLock) {
            if (!stopped && shouldObserveChanges()) {
                unresolvedCardIds.add(change.cardId());
                scheduleQueuedChanges();
            }
        }
    }

    public void onAttachmentChanged(AttachmentChangedEvent change) {
        if (!shouldObserveChanges()) {
            return;
        }
        synchronized (queueLock) {
            if (stopped || !shouldObserveChanges()) {
                return;
            }
            Set<String> fileNames = new LinkedHashSet<>();
            fileNames.add(change.fileName());
            if (change.previousFileName() != null) {
                fileNames.add(change.previousFileName());
            }
            for (String fileName : fileNames) {
                if (!SAFE_IMAGE_NAME.matcher(fileName).matches()) {
                    continue;
                }
                queuedNotes.addAll(indexState.attachmentToNotes().getOrDefault(fileName, List.of()));
            }
            if (!queuedNotes.isEmpty()) {
                scheduleQueuedChanges();
            }
        }
    }

    public void stop() {
        int pendingNotes;
        int pendingDeletes;
        int pendingCards;
        synchronized (queueLock) {
            stopped = true;
            pendingNotes = queuedNotes.size();
            pendingDeletes = deletedNotes.size();
            pendingCards = unresolvedCardIds.size();
            if (queuedChangesTask != null) {
                queuedChangesTask.cancel(false);
                queuedChangesTask = null;
            }
        }
        log.info("Graphify 索引服务正在停止：queuedNotes={}, queuedDeletes={}, unresolvedCards={}",
                pendingNotes, pendingDeletes, pendingCards);
        worker.shutdownNow();
        processRunner.stop();
    }

    public CompletableFuture<Void> resetState() {
        synchronized (queueLock) {
            if (stopped) {
                return CompletableFuture.failedFuture(new IllegalStateException("Graphify 索引线程已停止"));
            }
            resetting = true;
            queuedNotes.clear();
            deletedNotes.clear();
            unresolvedCardIds.clear();
            if (queuedChangesTask != null) {
                queuedChangesTask.cancel(false);
                queuedChangesTask = null;
            }
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
                ProjectionMetadata metadata = projectNote(note, warnings);
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
                    removeProjection(staleNoteId);
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
            String apiKey = requireApiKey(settings);
            currentStatus = statusFor("Graphify 抽取", true, 80, completed, notes.size(), warnings, null);
            runGraphifyPipeline(settings, apiKey, force, !previous.initialized());

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
        QueueSnapshot snapshot;
        synchronized (queueLock) {
            queuedChangesTask = null;
            snapshot = new QueueSnapshot(
                    new LinkedHashSet<>(queuedNotes),
                    new LinkedHashSet<>(deletedNotes),
                    new LinkedHashSet<>(unresolvedCardIds));
            queuedNotes.clear();
            deletedNotes.clear();
            unresolvedCardIds.clear();
        }
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
                removeProjection(deletedNoteId);
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
                    removeProjection(noteId);
                    noteIds.remove(noteId);
                    removeNoteMappings(noteId, cardToNote, attachmentToNotes);
                    projectionChanged = true;
                } else {
                    ProjectionMetadata metadata = projectNote(note, warnings);
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
            String apiKey = requireApiKey(settings);
            currentStatus = statusFor("Graphify 更新", true, 80, completed, changed.size(), warnings, null);
            runGraphifyPipeline(settings, apiKey, false, false);
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
            String apiKey = requireApiKey(settings);
            if (!Files.isRegularFile(graphJsonFile)) {
                throw new IllegalStateException("尚无图谱；请先完成首次索引");
            }
            currentStatus = statusFor("重新命名社区", true, 85, 0, 0, warnings, null);
            relabelCommunitiesNow(settings, apiKey);
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

    private void runGraphifyPipeline(GraphifySettingsService.Settings settings,
                                     String apiKey,
                                     boolean force,
                                     boolean labelFirstRun) throws IOException {
        long startedAt = System.nanoTime();
        Map<Path, FileBackup> backups = backupGraphOutputs();
        log.info("Graphify 图谱构建开始：force={}, firstRun={}", force, labelFirstRun);
        try {
            List<String> extract = new ArrayList<>(List.of(
                    "extract", corpusDir.toAbsolutePath().toString(),
                    "--backend", GraphifySettingsService.graphifyBackend(settings.provider()),
                    "--model", settings.model()));
            if (force) {
                extract.add("--force");
            }
            runCommand(settings, apiKey, extract, "抽取");

            List<String> cluster = new ArrayList<>(List.of(
                    "cluster-only", corpusDir.toAbsolutePath().toString()));
            if (labelFirstRun) {
                cluster.addAll(List.of("--backend", GraphifySettingsService.graphifyBackend(settings.provider()),
                        "--model", settings.model()));
            }
            runCommand(settings, apiKey, cluster, "社区聚类");
            if (!Files.isRegularFile(graphJsonFile)) {
                throw new IllegalStateException("Graphify 未生成 graph.json");
            }
            publishGraphOutputs();
            log.info("Graphify 图谱构建完成：elapsedMs={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (RuntimeException | IOException e) {
            restoreGraphOutputs(backups);
            throw e;
        }
    }

    private void runCommand(GraphifySettingsService.Settings settings,
                            String apiKey,
                            List<String> arguments,
                            String stage) throws IOException {
        GraphifyProcessRunner.Result result;
        try {
            result = processRunner.run(executable(settings), arguments, corpusDir, settings.provider(), apiKey);
        } catch (IOException e) {
            String component = GraphifySettingsService.OPENCODE_GO_PROVIDER.equals(settings.provider())
                    ? "Graphify 或 OpenCode Go API 转发器"
                    : "Graphify";
            throw new IllegalStateException("无法启动 " + component
                    + "；请确认 Graphify 已安装，或在插件设置中填写可执行文件路径", e);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Graphify " + stage + "失败（exit " + result.exitCode() + "）"
                    + diagnostic(result.output()));
        }
    }

    private ProjectionMetadata projectNote(NoteDTO note, List<String> warnings) {
        List<CardDTO> cards = cards(note.noteId());
        Map<String, CardDTO> cardsById = cards == null ? new HashMap<>() : cards.stream()
                .filter(card -> card != null && card.meta() != null && card.meta().cardId() != null)
                .collect(Collectors.toMap(card -> card.meta().cardId(), card -> card, (first, second) -> second));

        List<String> cardIds = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> order = note.cardIds() == null ? List.of() : note.cardIds();
        for (String cardId : order) {
            CardDTO card = cardsById.get(cardId);
            if (card == null) {
                continue;
            }
            cardIds.add(cardId);
            bodies.add(card.content() == null ? "" : card.content());
        }

        StringBuilder markdown = new StringBuilder("# ")
                .append(note.title() == null ? "" : note.title().replace('\n', ' ').replace('\r', ' '))
                .append("\n\n");
        for (int i = 0; i < bodies.size(); i++) {
            if (i > 0) {
                markdown.append("\n\n---\n\n");
            }
            markdown.append(bodies.get(i));
        }

        List<ImageReference> references = findImageReferences(markdown.toString());
        Set<String> attachmentNames = new LinkedHashSet<>();
        for (ImageReference reference : references) {
            attachmentNames.add(reference.fileName());
            String replacement = reference.originalTarget().startsWith("<")
                    || reference.fileName().contains(" ")
                    ? "<attachments/" + reference.fileName() + ">"
                    : "attachments/" + reference.fileName();
            markdown = new StringBuilder(markdown.toString().replace(reference.originalTarget(), replacement));
        }

        Path noteDir = noteDirectory(note.noteId());
        Set<String> copiedAttachments = new LinkedHashSet<>();
        boolean changed = false;
        for (String fileName : attachmentNames) {
            byte[] bytes = attachment(fileName);
            if (bytes == null) {
                warnings.add("图片附件不存在或无法读取：" + fileName);
                continue;
            }
            Path destination = noteDir.resolve("attachments").resolve(fileName);
            if (!sameBytes(destination, bytes)) {
                fileHelper.fileWritten(destination, new ByteArrayInputStream(bytes), 0);
                changed = true;
            }
            copiedAttachments.add(fileName);
            if (bytes.length > IMAGE_INLINE_LIMIT) {
                warnings.add("图片超过 Graphify 视觉输入上限 5 MiB，仍会作为图片引用索引：" + fileName);
            }
        }
        changed |= removeStaleAttachments(noteDir, copiedAttachments);
        Path noteFile = noteDir.resolve("note.md");
        if (!sameText(noteFile, markdown.toString())) {
            fileHelper.fileWritten(noteFile, markdown.toString());
            changed = true;
        }
        return new ProjectionMetadata(cardIds, attachmentNames, changed);
    }

    private List<ImageReference> findImageReferences(String markdown) {
        List<ImageReference> references = new ArrayList<>();
        Matcher matcher = MARKDOWN_IMAGE.matcher(markdown);
        while (matcher.find()) {
            String rawTarget = matcher.group(1).trim();
            boolean angleBracket = rawTarget.startsWith("<") && rawTarget.endsWith(">");
            String target = angleBracket ? rawTarget.substring(1, rawTarget.length() - 1) : rawTarget;
            Matcher attachment = ATTACHMENT_TARGET.matcher(target);
            if (!attachment.matches()) {
                continue;
            }
            String fileName = attachment.group(1).replace("%20", " ");
            if (!SAFE_IMAGE_NAME.matcher(fileName).matches()) {
                continue;
            }
            references.add(new ImageReference(rawTarget, fileName));
        }
        return references;
    }

    private boolean removeStaleAttachments(Path noteDir, Set<String> retained) {
        Path attachmentDir = noteDir.resolve("attachments");
        if (!Files.isDirectory(attachmentDir)) {
            return false;
        }
        try {
            List<Path> files;
            try (var listed = Files.list(attachmentDir)) {
                files = listed.toList();
            }
            boolean changed = false;
            for (Path file : files) {
                if (Files.isRegularFile(file) && !retained.contains(file.getFileName().toString())) {
                    if (!fileHelper.fileDeleted(file) && Files.exists(file)) {
                        log.error("删除 Graphify 文件失败：{}", file);
                    }
                    changed = true;
                }
            }
            boolean empty;
            try (var remaining = Files.list(attachmentDir)) {
                empty = remaining.findAny().isEmpty();
            }
            if (empty) {
                Files.deleteIfExists(attachmentDir);
            }
            return changed;
        } catch (Exception e) {
            throw new IllegalStateException("清理笔记投影图片失败", e);
        }
    }

    private static boolean sameText(Path path, String content) {
        try {
            return Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(content);
        } catch (Exception e) {
            log.error("读取 Graphify 文本投影失败：{}", path, e);
            return false;
        }
    }

    private static boolean sameBytes(Path path, byte[] content) {
        try {
            return Files.isRegularFile(path)
                    && Files.size(path) == content.length
                    && Arrays.equals(Files.readAllBytes(path), content);
        } catch (Exception e) {
            log.error("读取 Graphify 二进制投影失败：{}", path, e);
            return false;
        }
    }

    private void removeProjection(String noteId) {
        Path noteDir = noteDirectory(noteId);
        if (!Files.exists(noteDir)) {
            return;
        }
        try {
            List<Path> reverse;
            try (var paths = Files.walk(noteDir)) {
                reverse = paths.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : reverse) {
                if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    if (!fileHelper.fileDeleted(path) && Files.exists(path)) {
                        log.error("删除 Graphify 文件失败：{}", path);
                    }
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("删除笔记投影失败", e);
        }
    }

    private Path noteDirectory(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        Path target = corpusDir.resolve(noteId).normalize();
        if (!target.startsWith(corpusDir) || target.equals(corpusDir) || target.equals(graphifyOutDir)) {
            throw new IllegalArgumentException("noteId 不是有效的目录名称");
        }
        return target;
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

    private void relabelCommunitiesNow(GraphifySettingsService.Settings settings,
                                       String apiKey) throws IOException {
        if (!Files.isRegularFile(graphJsonFile)) {
            throw new IllegalStateException("尚无图谱；请先完成首次索引");
        }
        Map<Path, FileBackup> backups = backupGraphOutputs();
        try {
            runCommand(settings, apiKey, List.of(
                    "label", corpusDir.toAbsolutePath().toString(),
                    "--backend", GraphifySettingsService.graphifyBackend(settings.provider()),
                    "--model", settings.model()), "社区命名");
            publishGraphOutputs();
        } catch (RuntimeException | IOException e) {
            restoreGraphOutputs(backups);
            throw e;
        }
    }

    private String requireApiKey(GraphifySettingsService.Settings settings) {
        String apiKey = settingsService.apiKey(settings.provider());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("请先在 AI 能力插件中配置当前 Provider 的 API key");
        }
        return apiKey;
    }

    private static String executable(GraphifySettingsService.Settings settings) {
        return settings.executablePath() == null || settings.executablePath().isBlank()
                ? "graphify"
                : settings.executablePath();
    }

    private void queueNote(String noteId) {
        if (noteId == null || noteId.isBlank() || !shouldObserveChanges()) {
            return;
        }
        synchronized (queueLock) {
            if (!stopped && shouldObserveChanges() && !deletedNotes.contains(noteId)) {
                queuedNotes.add(noteId);
                scheduleQueuedChanges();
            }
        }
    }

    private void queueNoteDeletion(String noteId) {
        if (noteId == null || noteId.isBlank() || !shouldObserveChanges()) {
            return;
        }
        synchronized (queueLock) {
            if (!stopped && shouldObserveChanges()) {
                queuedNotes.remove(noteId);
                deletedNotes.add(noteId);
                scheduleQueuedChanges();
            }
        }
    }

    private boolean shouldObserveChanges() {
        return !resetting && (indexState.initialized() || currentStatus.running());
    }

    private void scheduleQueuedChanges() {
        if (queuedChangesTask != null) {
            queuedChangesTask.cancel(false);
        }
        queuedChangesTask = worker.schedule(this::processQueuedChanges, 2, TimeUnit.SECONDS);
        if (!currentStatus.running()) {
            currentStatus = statusFor("变更已排队", false, currentStatus.progress(),
                    currentStatus.completedNotes(), currentStatus.totalNotes(),
                    currentStatus.warnings(), currentStatus.error());
        }
    }

    private void scheduleQueuedChangesIfReady() {
        if (indexState.initialized()
                && (!queuedNotes.isEmpty() || !deletedNotes.isEmpty() || !unresolvedCardIds.isEmpty())) {
            scheduleQueuedChanges();
        }
    }

    private void restoreQueue(QueueSnapshot snapshot) {
        synchronized (queueLock) {
            queuedNotes.addAll(snapshot.noteIds());
            deletedNotes.addAll(snapshot.deletedNoteIds());
            unresolvedCardIds.addAll(snapshot.unresolvedCardIds());
        }
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

    private Map<Path, FileBackup> backupGraphOutputs() throws IOException {
        Map<Path, FileBackup> backups = new LinkedHashMap<>();
        for (Path path : graphifyOutputFiles()) {
            if (Files.isRegularFile(path)) {
                backups.put(path, new FileBackup(Files.readAllBytes(path),
                        Files.getLastModifiedTime(path).toMillis()));
            } else {
                backups.put(path, null);
            }
        }
        return backups;
    }

    private void restoreGraphOutputs(Map<Path, FileBackup> backups) {
        for (Map.Entry<Path, FileBackup> entry : backups.entrySet()) {
            Path path = entry.getKey();
            FileBackup backup = entry.getValue();
            if (backup == null) {
                if (!fileHelper.fileDeleted(path) && Files.exists(path)) {
                    log.error("删除 Graphify 文件失败：{}", path);
                }
            } else {
                fileHelper.fileWritten(path, new ByteArrayInputStream(backup.bytes()), backup.modifiedAt());
            }
        }
    }

    private void publishGraphOutputs() throws IOException {
        int published = 0;
        for (Path path : graphifyOutputFiles()) {
            if (Files.isRegularFile(path)) {
                byte[] bytes = Files.readAllBytes(path);
                FileTime modifiedAt = Files.getLastModifiedTime(path);
                fileHelper.fileWritten(path, new ByteArrayInputStream(bytes), modifiedAt.toMillis());
                published++;
            }
        }
        log.info("Graphify 图谱输出已发布：files={}", published);
    }

    private List<Path> graphifyOutputFiles() {
        return List.of(
                graphJsonFile,
                graphHtmlFile,
                graphifyOutDir.resolve("GRAPH_REPORT.md"),
                graphifyOutDir.resolve(".graphify_labels.json"),
                graphifyOutDir.resolve(".graphify_analysis.json")
        );
    }

    private static String noteIdFromSource(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return null;
        }
        String source = sourceFile.replace('\\', '/');
        while (source.startsWith("./")) {
            source = source.substring(2);
        }
        int slash = source.indexOf('/');
        return slash < 0 ? null : source.substring(0, slash);
    }

    private static String endpointId(Object value) {
        if (value instanceof JSONObject object) {
            return object.getString("id");
        }
        return value == null ? null : String.valueOf(value);
    }

    private static String noteTitle(NoteDTO note) {
        if (note == null || note.title() == null || note.title().isBlank()) {
            return "未命名笔记";
        }
        return note.title();
    }

    private static String normalizeLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double relationWeight(String relation) {
        if (relation == null) {
            return 3.0;
        }
        return switch (relation) {
            case "semantically_similar_to" -> 8.0;
            case "conceptually_related_to" -> 7.0;
            case "references", "cites" -> 4.0;
            case "shares_data_with" -> 4.0;
            default -> 3.0;
        };
    }

    private static String relationLabel(String relation) {
        if (relation == null || relation.isBlank()) {
            return "关联";
        }
        return switch (relation) {
            case "semantically_similar_to" -> "语义相似";
            case "conceptually_related_to" -> "概念相关";
            case "references" -> "引用";
            case "cites" -> "引用";
            case "shares_data_with" -> "共享数据";
            case "calls" -> "调用";
            case "implements" -> "实现";
            default -> relation;
        };
    }

    private static List<String> relatedReasons(RelatedCandidate candidate,
                                               Map<String, Integer> conceptFrequency) {
        List<String> reasons = new ArrayList<>();
        if (!candidate.relationLabels.isEmpty()) {
            reasons.add("图谱关系：" + String.join("、", candidate.relationLabels));
        }
        if (!candidate.sharedConcepts.isEmpty()) {
            candidate.sharedConcepts.sort(
                    Comparator.comparingInt((ConceptMatch match) -> conceptFrequency.getOrDefault(match.key(), 1))
                            .thenComparing(ConceptMatch::label, String.CASE_INSENSITIVE_ORDER));
            List<String> labels = candidate.sharedConcepts.stream()
                    .limit(3)
                    .map(ConceptMatch::label)
                    .toList();
            String detail = String.join("、", labels);
            if (candidate.sharedConcepts.size() > labels.size()) {
                detail += " 等 " + candidate.sharedConcepts.size() + " 个";
            }
            reasons.add("共同概念：" + detail);
        }
        if (!candidate.sharedCommunities.isEmpty() && reasons.size() < 3) {
            reasons.add("同属社区：" + candidate.sharedCommunities.iterator().next());
        }
        if (reasons.size() > 3) {
            reasons = new ArrayList<>(reasons.subList(0, 3));
        }
        return List.copyOf(reasons);
    }

    private static boolean isMeaningfulTag(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        String value = label.trim();
        if (PASTE_ASSET_REFERENCE.matcher(value).find()) {
            return false;
        }
        if (IMAGE_PLACEHOLDER.matcher(value).matches()) {
            return false;
        }
        return !HASH_ONLY.matcher(value).matches();
    }

    private Status statusFor(String stage,
                             boolean running,
                             int progress,
                             int completedNotes,
                             int totalNotes,
                             List<String> warnings,
                             String error) {
        int queued;
        synchronized (queueLock) {
            queued = queuedNotes.size() + deletedNotes.size() + unresolvedCardIds.size();
        }
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

    private static String diagnostic(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String important = output.lines()
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error")
                            || lower.contains("failed")
                            || lower.contains("incomplete")
                            || lower.contains("shrink")
                            || lower.contains("semantic cache");
                })
                .distinct()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (!important.isBlank()) {
            output = important;
        }
        String trimmed = output.trim();
        if (trimmed.length() > 1800) {
            trimmed = trimmed.substring(trimmed.length() - 1800);
        }
        return "：" + trimmed;
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

    public record NoteTags(List<String> concept, List<String> community) {
    }

    public record RelatedNotes(boolean available, List<RelatedNote> notes) {
    }

    public record RelatedNote(String noteId, String title, List<String> reasons) {
    }

    private record ProjectionMetadata(List<String> cardIds, Set<String> attachmentNames, boolean changed) {
    }

    private record ImageReference(String originalTarget, String fileName) {
    }

    private record FileBackup(byte[] bytes, long modifiedAt) {
    }

    private record QueueSnapshot(Set<String> noteIds,
                                 Set<String> deletedNoteIds,
                                 Set<String> unresolvedCardIds) {

        private boolean isEmpty() {
            return noteIds.isEmpty() && deletedNoteIds.isEmpty() && unresolvedCardIds.isEmpty();
        }
    }

    private static final class RelatedCandidate {
        private final String noteId;
        private double score;
        private final Set<String> relationLabels = new LinkedHashSet<>();
        private final List<ConceptMatch> sharedConcepts = new ArrayList<>();
        private final Set<String> sharedCommunities = new LinkedHashSet<>();

        private RelatedCandidate(String noteId) {
            this.noteId = noteId;
        }
    }

    private record ConceptMatch(String key, String label) {
    }
}
