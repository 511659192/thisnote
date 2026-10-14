package com.thisnode.ext.notes.controller;

import com.thisnode.ext.notes.model.Card;
import com.thisnode.ext.notes.model.request.*;
import com.thisnode.ext.notes.model.view.NoteDto;
import com.thisnode.ext.notes.service.CardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/notes/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /**
     * 这些卡片 ID 省略表示整页：返回每张卡片的几何与正文。
     */
    @GetMapping("/get")
    public NoteDto get(@RequestParam String noteId,
                       @RequestParam(required = false) List<String> cardIds) {
        return cardService.cards(noteId, cardIds);
    }

    @PostMapping("/create")
    public Card create(@RequestParam String noteId,
                       @RequestBody(required = false) CreateCardRequest req) {
        return cardService.create(noteId, req == null ? new CreateCardRequest() : req);
    }

    /**
     * Write markdown typed inside a card back into the note.
     */
    @PostMapping("/update-content")
    public Card writeContent(@RequestBody WriteCardContentRequest req) {
        return cardService.writeContent(req.noteId(), req.cardId(), req.contentMd(), req.baseContentMd());
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody BatchUpdateCardsRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/update")
    public Card.CardMeta update(@RequestBody UpdateCardRequest changes) {
        return cardService.update(changes.noteId(), changes.cardId(), changes);
    }

    @PostMapping("/delete")
    public void delete(@RequestParam String noteId,
                       @RequestParam String cardId) {
        cardService.delete(noteId, cardId);
    }

}
