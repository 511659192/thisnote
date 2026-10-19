package com.thisnote.ext.graphify.controller;

import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.service.GraphifySettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/plugins/graphify")
public class GraphifyController {

    private final GraphifyIndexService graphifyService;
    private final GraphifySettingsService settingsService;

    public GraphifyController(GraphifyIndexService graphifyService,
                              GraphifySettingsService settingsService) {
        this.graphifyService = graphifyService;
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
        JSONObject graph = graphifyService.graph();
        return graph == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(graph);
    }

    @GetMapping(value = "/graph.html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> graphHtml() {
        String html = graphifyService.graphHtml();
        return html == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(html);
    }

    @GetMapping("/tags")
    public Map<String, GraphifyIndexService.NoteTags> tags() {
        return graphifyService.tags();
    }

    @GetMapping("/related")
    public GraphifyIndexService.RelatedNotes related(@RequestParam String noteId) {
        return graphifyService.relatedNotes(noteId);
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
