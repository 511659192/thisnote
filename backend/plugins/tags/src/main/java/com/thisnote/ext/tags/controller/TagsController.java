package com.thisnote.ext.tags.controller;

import com.thisnote.ext.tags.service.AutoTagService;
import com.thisnote.ext.tags.service.TagUsageService;
import com.thisnote.ext.tags.service.TagsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins/tags")
public class TagsController {

    private final TagsService tagsService;
    private final AutoTagService autoTagService;
    private final TagUsageService tagUsageService;

    public TagsController(TagsService tagsService,
                          AutoTagService autoTagService,
                          TagUsageService tagUsageService) {
        this.tagsService = tagsService;
        this.autoTagService = autoTagService;
        this.tagUsageService = tagUsageService;
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
        return new NoteTagsResponse(request.noteId().trim(), tags);
    }

    @PostMapping("/tags/remove")
    public NoteTagsResponse remove(@RequestBody TagRequest request) {
        List<String> tags = tagsService.remove(request.noteId(), request.tag());
        return new NoteTagsResponse(request.noteId().trim(), tags);
    }

    @PostMapping("/tags/delete")
    public DeleteTagResponse delete(@RequestBody DeleteTagRequest request) {
        int removedNotes = tagsService.removeEverywhere(request.tag());
        return new DeleteTagResponse(removedNotes);
    }

    @PostMapping("/tags/auto")
    public NoteTagsResponse auto(@RequestBody NoteRequest request) {
        List<String> tags = autoTagService.extract(request.noteId());
        return new NoteTagsResponse(request.noteId().trim(), tags);
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
