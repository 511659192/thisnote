package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagRepository tagRepository;

    @GetMapping
    public List<Dtos.TagDto> list() {
        return tagRepository.listWithCounts();
    }

    @PutMapping("/{id}")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        if (body.containsKey("color") && !body.containsKey("name")) {
            tagRepository.setColor(id, body.get("color"));
        } else {
            tagRepository.rename(id, body.getOrDefault("name", ""));
            if (body.get("color") != null) {
                tagRepository.setColor(id, body.get("color"));
            }
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        tagRepository.delete(id);
        return Map.of("ok", true);
    }
}
