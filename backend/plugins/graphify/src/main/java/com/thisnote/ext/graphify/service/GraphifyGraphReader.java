package com.thisnote.ext.graphify.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.repository.GraphifyStateRepository;
import com.thisnote.plugin.api.event.NoteListFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 只读访问 Graphify 生成的 graph.json。
 *
 * <p>标签和相关笔记查询频率较高，而 graph.json 只会在索引后变化，
 * 因此按 mtime + size 缓存解析结果。
 */
@Service
@Slf4j
public class GraphifyGraphReader {

    private static final Pattern PASTE_ASSET_REFERENCE = Pattern.compile("paste-[0-9a-f]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile("^(?:pasted image(?: note| attachment)?|referenced image attachment\\b.*|image attachment\\s*:.*|paste note\\s*:.*)$",
                                                                     Pattern.CASE_INSENSITIVE);
    private static final Pattern HASH_ONLY = Pattern.compile("^(?:[0-9a-f]{16,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$", Pattern.CASE_INSENSITIVE);
    private static final String POST_MESSAGE_MARKER = "thisnote-graphify-open-note";
    private static final int RELATED_NOTES_LIMIT = 5;
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final Path graphJsonFile;
    private final Path graphHtmlFile;
    private final GraphifyStateRepository stateRepository;
    private ApplicationEventPublisher eventPublisher;
    private volatile CachedGraph cachedGraph;

    public GraphifyGraphReader(@Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot,
                               GraphifyStateRepository stateRepository) {
        Path graphifyOutDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("graphify").resolve("corpus").resolve("graphify-out");
        this.graphJsonFile = graphifyOutDir.resolve("graph.json");
        this.graphHtmlFile = graphifyOutDir.resolve("graph.html");
        this.stateRepository = stateRepository;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public JSONObject graph() {
        if (!Files.isRegularFile(graphJsonFile)) {
            log.debug("Graphify graph.json 不存在：file={}", graphJsonFile);
            return null;
        }
        try {
            long modifiedAt = Files.getLastModifiedTime(graphJsonFile).toMillis();
            long size = Files.size(graphJsonFile);
            CachedGraph cached = cachedGraph;
            if (cached != null && cached.modifiedAt() == modifiedAt && cached.size() == size) {
                log.debug("Graphify graph.json 命中缓存：file={}, size={}", graphJsonFile, size);
                return cached.graph();
            }
            JSONObject graph = JSON.parseObject(Files.readString(graphJsonFile, StandardCharsets.UTF_8));
            cachedGraph = new CachedGraph(modifiedAt, size, graph);
            log.debug("Graphify graph.json 已重新解析：file={}", graphJsonFile);
            return graph;
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
                    """.formatted(POST_MESSAGE_MARKER);
            return html.replace(anchor, hook + "\n" + anchor);
        } catch (Exception e) {
            throw new IllegalStateException("Graphify graph.html 无法读取", e);
        }
    }

    public Map<String, NoteTags> tags() {
        JSONObject graph = graph();
        if (graph == null) {
            log.debug("Graphify 标签查询：graph.json 不存在，仅使用索引状态");
        }
        TagSignals signals = graph == null ? new TagSignals(Map.of(), Map.of()) : collectTagSignals(graph);
        return buildTagResult(signals.concepts(), signals.communities());
    }

    private static TagSignals collectTagSignals(JSONObject graph) {
        Map<String, Set<String>> concepts = new LinkedHashMap<>();
        Map<String, Set<String>> communities = new LinkedHashMap<>();
        JSONArray nodes = graph.getJSONArray("nodes");
        if (nodes == null) {
            return new TagSignals(concepts, communities);
        }
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
            if (isMeaningfulTag(communityName)) {
                communities.computeIfAbsent(noteId, ignored -> new LinkedHashSet<>()).add(communityName.trim());
            }
        }
        return new TagSignals(concepts, communities);
    }

    private Map<String, NoteTags> buildTagResult(Map<String, Set<String>> concepts,
                                                 Map<String, Set<String>> communities) {
        Set<String> noteIds = new LinkedHashSet<>(stateRepository.getIndexState().noteIds());
        noteIds.addAll(concepts.keySet());
        noteIds.addAll(communities.keySet());
        Map<String, NoteTags> result = new LinkedHashMap<>();
        for (String noteId : noteIds) {
            List<String> conceptTags = concepts.getOrDefault(noteId, Set.of()).stream().sorted().toList();
            List<String> communityTags = communities.getOrDefault(noteId, Set.of()).stream().sorted().toList();
            result.put(noteId, new NoteTags(conceptTags, communityTags));
        }
        log.debug("Graphify 标签查询完成：notes={}", result.size());
        return result;
    }

    public RelatedNotes relatedNotes(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        JSONObject graph = graph();
        if (graph == null) {
            log.debug("Graphify 相关笔记查询跳过：graph.json 不存在");
            return new RelatedNotes(false, List.of());
        }

        Map<String, NoteDTO> notesById = notesById(allNotes());
        if (!notesById.containsKey(noteId)) {
            return new RelatedNotes(true, List.of());
        }

        GraphSignals signals = collectGraphSignals(graph);
        removeTitleConcepts(signals.conceptsByNote(), notesById);
        Map<String, Integer> conceptFrequency = conceptFrequency(signals.conceptsByNote());
        Map<String, RelatedCandidate> candidates = conceptCandidates(noteId, signals.conceptsByNote(), conceptFrequency);
        mergeRelationCandidates(graph, noteId, signals.nodeNotes(), notesById, candidates);
        mergeCommunityScores(noteId, signals.communitiesByNote(), candidates);
        List<RelatedNote> related = rankRelated(notesById, candidates, conceptFrequency);
        log.debug("Graphify 相关笔记查询完成：noteId={}, related={}", noteId, related.size());
        return new RelatedNotes(true, related);
    }

    private static Map<String, NoteDTO> notesById(List<NoteDTO> notes) {
        Map<String, NoteDTO> result = new LinkedHashMap<>();
        for (NoteDTO note : notes) {
            if (note != null && note.noteId() != null) {
                result.put(note.noteId(), note);
            }
        }
        return result;
    }

    private static GraphSignals collectGraphSignals(JSONObject graph) {
        Map<String, Map<String, String>> conceptsByNote = new LinkedHashMap<>();
        Map<String, Set<String>> communitiesByNote = new LinkedHashMap<>();
        Map<String, String> nodeNotes = new HashMap<>();
        JSONArray nodes = graph.getJSONArray("nodes");
        if (nodes != null) {
            for (Object value : nodes) {
                if (!(value instanceof JSONObject node)) {
                    continue;
                }
                addNodeSignals(node, conceptsByNote, communitiesByNote, nodeNotes);
            }
        }
        return new GraphSignals(conceptsByNote, communitiesByNote, nodeNotes);
    }

    private static void addNodeSignals(JSONObject node,
                                       Map<String, Map<String, String>> conceptsByNote,
                                       Map<String, Set<String>> communitiesByNote,
                                       Map<String, String> nodeNotes) {
        String ownerNoteId = noteIdFromSource(node.getString("source_file"));
        String nodeId = node.getString("id");
        if (ownerNoteId == null || nodeId == null) {
            return;
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

    private static void removeTitleConcepts(Map<String, Map<String, String>> conceptsByNote,
                                            Map<String, NoteDTO> notesById) {
        notesById.forEach((noteId, note) -> {
            Map<String, String> concepts = conceptsByNote.get(noteId);
            if (concepts != null) {
                concepts.remove(normalizeLabel(note.title()));
            }
        });
    }

    private static Map<String, Integer> conceptFrequency(Map<String, Map<String, String>> conceptsByNote) {
        Map<String, Integer> frequency = new HashMap<>();
        conceptsByNote.values().forEach(concepts ->
                concepts.keySet().forEach(concept -> frequency.merge(concept, 1, Integer::sum)));
        return frequency;
    }

    private static Map<String, RelatedCandidate> conceptCandidates(String noteId,
                                                                   Map<String, Map<String, String>> conceptsByNote,
                                                                   Map<String, Integer> conceptFrequency) {
        Map<String, RelatedCandidate> candidates = new LinkedHashMap<>();
        Map<String, String> currentConcepts = conceptsByNote.getOrDefault(noteId, Map.of());
        conceptsByNote.forEach((otherNoteId, concepts) -> {
            if (otherNoteId.equals(noteId)) {
                return;
            }
            currentConcepts.forEach((key, label) -> {
                if (!concepts.containsKey(key)) {
                    return;
                }
                RelatedCandidate candidate = candidates.computeIfAbsent(otherNoteId, RelatedCandidate::new);
                candidate.score += 6.0 / conceptFrequency.getOrDefault(key, 1);
                candidate.sharedConcepts.add(new ConceptMatch(key, label));
            });
        });
        return candidates;
    }

    private static void mergeRelationCandidates(JSONObject graph,
                                                String noteId,
                                                Map<String, String> nodeNotes,
                                                Map<String, NoteDTO> notesById,
                                                Map<String, RelatedCandidate> candidates) {
        JSONArray links = graph.getJSONArray("links");
        if (links == null) {
            return;
        }
        for (Object value : links) {
            if (!(value instanceof JSONObject link)) {
                continue;
            }
            String sourceNoteId = nodeNotes.get(endpointId(link.get("source")));
            String targetNoteId = nodeNotes.get(endpointId(link.get("target")));
            String otherNoteId = otherNoteId(noteId, sourceNoteId, targetNoteId);
            if (otherNoteId == null || !notesById.containsKey(otherNoteId)) {
                continue;
            }
            addRelationScore(link, candidates.computeIfAbsent(otherNoteId, RelatedCandidate::new));
        }
    }

    private static String otherNoteId(String noteId,
                                      String sourceNoteId,
                                      String targetNoteId) {
        if (noteId.equals(sourceNoteId) && targetNoteId != null && !noteId.equals(targetNoteId)) {
            return targetNoteId;
        }
        if (noteId.equals(targetNoteId) && sourceNoteId != null && !noteId.equals(sourceNoteId)) {
            return sourceNoteId;
        }
        return null;
    }

    private static void addRelationScore(JSONObject link, RelatedCandidate candidate) {
        String relation = link.getString("relation");
        if (!candidate.relationLabels.add(relationLabel(relation))) {
            return;
        }
        double confidence = link.getDoubleValue("confidence_score");
        if (confidence <= 0) {
            confidence = 1.0;
        }
        candidate.score += relationWeight(relation) * (0.5 + 0.5 * Math.min(1.0, confidence));
    }

    private static void mergeCommunityScores(String noteId,
                                             Map<String, Set<String>> communitiesByNote,
                                             Map<String, RelatedCandidate> candidates) {
        Set<String> currentCommunities = communitiesByNote.getOrDefault(noteId, Set.of());
        candidates.values().forEach(candidate -> {
            Set<String> otherCommunities = communitiesByNote.get(candidate.noteId);
            if (otherCommunities == null) {
                return;
            }
            currentCommunities.stream()
                    .filter(otherCommunities::contains)
                    .forEach(candidate.sharedCommunities::add);
            if (!candidate.sharedCommunities.isEmpty()) {
                candidate.score += 1.0;
            }
        });
    }

    private static List<RelatedNote> rankRelated(Map<String, NoteDTO> notesById,
                                                 Map<String, RelatedCandidate> candidates,
                                                 Map<String, Integer> conceptFrequency) {
        return candidates.values().stream()
                .filter(candidate -> notesById.containsKey(candidate.noteId))
                .sorted(Comparator.comparingDouble((RelatedCandidate candidate) -> candidate.score)
                        .reversed()
                        .thenComparing(candidate -> noteTitle(notesById.get(candidate.noteId)),
                                String.CASE_INSENSITIVE_ORDER))
                .limit(RELATED_NOTES_LIMIT)
                .map(candidate -> new RelatedNote(
                        candidate.noteId,
                        noteTitle(notesById.get(candidate.noteId)),
                        relatedReasons(candidate, conceptFrequency)))
                .toList();
    }

    private List<NoteDTO> allNotes() {
        if (eventPublisher == null) {
            throw new IllegalStateException("Notes 消息管道尚未就绪");
        }
        CompletableFuture<List<NoteDTO>> result = new CompletableFuture<>();
        eventPublisher.publishEvent(new NoteListFutureEvent(result));
        List<NoteDTO> notes = result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        log.debug("Graphify 相关笔记查询读取笔记完成：notes={}", notes.size());
        return notes;
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
                    Comparator.comparingInt((ConceptMatch match) -> conceptFrequency.getOrDefault(match.key(), 1)).thenComparing(ConceptMatch::label, String.CASE_INSENSITIVE_ORDER));
            List<String> labels = candidate.sharedConcepts.stream().limit(3).map(ConceptMatch::label).toList();
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
        return reasons;
    }

    private static boolean isMeaningfulTag(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        String value = label.trim();
        return !PASTE_ASSET_REFERENCE.matcher(value).find() && !IMAGE_PLACEHOLDER.matcher(value).matches() && !HASH_ONLY.matcher(value).matches();
    }

    public record NoteTags(List<String> concept, List<String> community) {
    }

    public record RelatedNotes(boolean available, List<RelatedNote> notes) {
    }

    public record RelatedNote(String noteId, String title, List<String> reasons) {
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

    private record TagSignals(Map<String, Set<String>> concepts,
                              Map<String, Set<String>> communities) {
    }

    private record GraphSignals(Map<String, Map<String, String>> conceptsByNote,
                                Map<String, Set<String>> communitiesByNote,
                                Map<String, String> nodeNotes) {
    }

    private record CachedGraph(long modifiedAt, long size, JSONObject graph) {
    }
}
