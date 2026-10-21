package com.thisnote.ext.graphify.controller;

import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.service.GraphifyGraphReader;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.service.GraphifyNlpSearchService;
import com.thisnote.ext.graphify.service.GraphifySettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/plugins/graphify")
@Slf4j
public class GraphifyController {

    private final GraphifyIndexService graphifyService;
    private final GraphifyGraphReader graphReader;
    private final GraphifyNlpSearchService nlpSearchService;
    private final GraphifySettingsService settingsService;

    public GraphifyController(GraphifyIndexService graphifyService,
                              GraphifyGraphReader graphReader,
                              GraphifyNlpSearchService nlpSearchService,
                              GraphifySettingsService settingsService) {
        this.graphifyService = graphifyService;
        this.graphReader = graphReader;
        this.nlpSearchService = nlpSearchService;
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public GraphifySettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public GraphifySettingsService.SettingsResponse saveSettings(
            @RequestBody GraphifySettingsService.SettingsUpdate update) {
        GraphifySettingsService.SettingsResponse response = settingsService.save(update);
        log.info("Graphify 设置已保存：aiProfileId={}", response.aiProfileId());
        return response;
    }

    @GetMapping("/status")
    public GraphifyIndexService.Status status() {
        return graphifyService.status();
    }

    @PostMapping("/index")
    public ResponseEntity<IndexResponse> index(@RequestBody(required = false) IndexRequest request) {
        boolean force = request != null && request.force();
        log.info("收到 Graphify 索引请求：force={}", force);
        graphifyService.requestIndex(force);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IndexResponse(true, force));
    }

    @GetMapping("/graph")
    public ResponseEntity<JSONObject> graph() {
        JSONObject graph = graphReader.graph();
        return graph == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(graph);
    }

    @GetMapping(value = "/graph.html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> graphHtml() {
        String html = graphReader.graphHtml();
        return html == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(html);
    }

    @GetMapping("/tags")
    public Map<String, GraphifyGraphReader.NoteTags> tags() {
        return graphReader.tags();
    }

    @GetMapping("/related")
    public GraphifyGraphReader.RelatedNotes related(@RequestParam String noteId) {
        log.debug("查询 Graphify 相关笔记：noteId={}", noteId);
        return graphReader.relatedNotes(noteId);
    }

    @GetMapping("/search")
    public GraphifyNlpSearchService.SearchResponse search(@RequestParam String query) {
        log.info("收到 Graphify NLP 查询：queryLength={}", query == null ? 0 : query.length());
        return nlpSearchService.search(query);
    }

    @PostMapping("/communities/relabel")
    public ResponseEntity<Map<String, Boolean>> relabelCommunities() {
        log.info("收到 Graphify 社区命名请求");
        graphifyService.requestRelabel();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", true));
    }

    public record IndexRequest(boolean force) {
    }

    public record IndexResponse(boolean accepted, boolean force) {
    }

}
