package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.TagRepository;
import com.thisnode.ext.notes.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final NoteService noteService;
    private final TagRepository tagRepository;

    @GetMapping
    public List<Dtos.TagDto> list() {
        return noteService.tagCatalog();
    }

    @PostMapping("/update")
    public Map<String, Object> rename(@RequestParam String id, @RequestBody Map<String, String> body) {
        if (body.containsKey("color") && !body.containsKey("name")) {
            tagRepository.setColor(id, body.get("color"));
        } else {
            noteService.renameTag(id, body.getOrDefault("name", ""));
            if (body.get("color") != null) {
                tagRepository.setColor(id, body.get("color"));
            }
        }
        return Map.of("ok", true);
    }

}
