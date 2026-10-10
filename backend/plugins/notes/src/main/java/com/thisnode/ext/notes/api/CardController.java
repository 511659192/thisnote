package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.service.CardService;
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
@RequestMapping("/api/notes/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /** 这几张卡（{@code ids} 省略 = 整页）：卡片几何 + 它自己的正文。 */
    @GetMapping
    public Dtos.CardsDto get(@RequestParam String noteId,
                                  @RequestParam(required = false) List<String> ids) {
        return cardService.page(noteId, ids);
    }

    @PostMapping("/create")
    public Dtos.CardView create(@RequestParam String noteId,
                                     @RequestBody(required = false) Dtos.CreateCardRequest req) {
        return cardService.create(noteId, req);
    }

    /** Write markdown typed inside a card back into the note. */
    @PostMapping("/content")
    public Dtos.CardView writeContent(@RequestParam String cardId,
                                           @RequestBody(required = false) Dtos.WriteContentRequest req) {
        return cardService.writeContent(cardId,
                req == null || req.contentMd() == null ? "" : req.contentMd(),
                req == null ? null : req.baseContentMd());
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody Dtos.BatchCardUpdateRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/update")
    public Dtos.CardDto update(@RequestParam String cardId, @RequestBody Dtos.CardUpdate changes) {
        return cardService.update(cardId, changes);
    }

    @PostMapping("/duplicate")
    public Dtos.CardDto duplicate(@RequestParam String cardId,
                                       @RequestParam(defaultValue = "28") double dx,
                                       @RequestParam(defaultValue = "28") double dy) {
        return cardService.duplicate(cardId, dx, dy);
    }

    @PostMapping("/z")
    public Dtos.CardDto zOrder(@RequestBody Map<String, String> body) {
        return cardService.zOrder(body.getOrDefault("id", ""), body.getOrDefault("op", "top"));
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String cardId,
                                      @RequestParam(defaultValue = "false") boolean deleteContent) {
        cardService.delete(cardId, deleteContent);
        return Map.of("ok", true);
    }
}
