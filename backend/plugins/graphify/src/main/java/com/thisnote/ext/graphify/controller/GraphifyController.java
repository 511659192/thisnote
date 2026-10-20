package com.thisnote.ext.graphify.controller;

import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.service.GraphifyGraphReader;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.service.GraphifySettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/plugins/graphify")
public class GraphifyController {

    private final GraphifyIndexService graphifyService;
    private final GraphifyGraphReader graphReader;
    private final GraphifySettingsService settingsService;

    public GraphifyController(GraphifyIndexService graphifyService,
                              GraphifyGraphReader graphReader,
                              GraphifySettingsService settingsService) {
        this.graphifyService = graphifyService;
        this.graphReader = graphReader;
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public GraphifySettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public GraphifySettingsService.SettingsResponse saveSettings(
            @RequestBody GraphifySettingsService.SettingsUpdate update) {
        return settingsService.save(update);
    }

    @GetMapping("/status")
    public GraphifyIndexService.Status status() {
        return graphifyService.status();
    }

    @PostMapping("/index")
    public ResponseEntity<IndexResponse> index(@RequestBody(required = false) IndexRequest request) {
        boolean force = request != null && request.force();
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
        return graphReader.relatedNotes(noteId);
    }

    @PostMapping("/communities/relabel")
    public ResponseEntity<Map<String, Boolean>> relabelCommunities() {
        graphifyService.requestRelabel();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", true));
    }

    public record IndexRequest(boolean force) {
    }

    public record IndexResponse(boolean accepted, boolean force) {
    }

}
