package com.thisnote.ext.tags.controller;

import com.thisnote.ext.tags.service.AutoTagService;
import com.thisnote.ext.tags.service.TagUsageService;
import com.thisnote.ext.tags.service.TagsSettingsService;
import com.thisnote.ext.tags.service.TagsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins/tags")
@Slf4j
public class TagsController {

    private final TagsService tagsService;
    private final AutoTagService autoTagService;
    private final TagUsageService tagUsageService;
    private final TagsSettingsService settingsService;

    public TagsController(TagsService tagsService,
                          AutoTagService autoTagService,
                          TagUsageService tagUsageService,
                          TagsSettingsService settingsService) {
        this.tagsService = tagsService;
        this.autoTagService = autoTagService;
        this.tagUsageService = tagUsageService;
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public TagsSettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public TagsSettingsService.SettingsResponse saveSettings(
            @RequestBody TagsSettingsService.SettingsUpdate update) {
        TagsSettingsService.SettingsResponse response = settingsService.save(update);
        log.info("标签设置已保存：aiProfileId={}", response.aiProfileId());
        return response;
    }

    @GetMapping("/tags")
    public Map<String, List<String>> tags() {
        return tagsService.all();
    }

    @GetMapping("/tags/usage")
    public Map<String, Integer> usage() {
        return tagUsageService.occurrences();
    }

    @PostMapping("/tags/add")
    public NoteTagsResponse add(@RequestBody TagRequest request) {
        List<String> tags = tagsService.add(request.noteId(), request.tag());
        log.info("标签添加完成：noteId={}, tag={}, total={}",
                request.noteId(), request.tag(), tags.size());
        return new NoteTagsResponse(request.noteId().trim(), tags);
    }

    @PostMapping("/tags/remove")
    public NoteTagsResponse remove(@RequestBody TagRequest request) {
        List<String> tags = tagsService.remove(request.noteId(), request.tag());
        log.info("标签移除完成：noteId={}, tag={}, total={}",
                request.noteId(), request.tag(), tags.size());
        return new NoteTagsResponse(request.noteId().trim(), tags);
    }

    @PostMapping("/tags/delete")
    public DeleteTagResponse delete(@RequestBody DeleteTagRequest request) {
        int removedNotes = tagsService.removeEverywhere(request.tag());
        log.info("标签全局删除完成：tag={}, removedNotes={}", request.tag(), removedNotes);
        return new DeleteTagResponse(removedNotes);
    }

    @PostMapping("/tags/auto")
    public AutoTagService.ExtractResult auto(@RequestBody NoteRequest request) {
        log.info("收到自动标签请求：noteId={}", request.noteId());
        return autoTagService.extract(request.noteId());
    }

    @GetMapping("/tags/auto/status")
    public AutoTagService.ExtractStatus autoStatus(@RequestParam String noteId) {
        log.debug("查询自动标签状态：noteId={}", noteId);
        return autoTagService.status(noteId);
    }

    public record NoteRequest(String noteId) {
    }

    public record TagRequest(String noteId, String tag) {
    }

    public record NoteTagsResponse(String noteId, List<String> tags) {
    }

    public record DeleteTagRequest(String tag) {
    }

    public record DeleteTagResponse(int removedNotes) {
    }
}
