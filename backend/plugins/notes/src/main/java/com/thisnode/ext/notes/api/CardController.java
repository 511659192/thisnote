package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.*;
import com.thisnode.ext.notes.domain.Card;
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
     * 这几张卡（{@code cardFileNames} 省略 = 整页）：卡片几何 + 它自己的正文。
     */
    @GetMapping
    public NoteDto get(@RequestParam String noteId,
                       @RequestParam(required = false) List<String> cardFileNames) {
        return cardService.cards(noteId, cardFileNames);
    }

    @PostMapping("/create")
    public Card create(@RequestParam String noteId,
                       @RequestBody(required = false) CreateCardRequest req) {
        return cardService.create(noteId, req == null ? new CreateCardRequest() : req);
    }

    /**
     * Write markdown typed inside a card back into the note.
     */
    @PostMapping("/content")
    public Card writeContent(@RequestBody WriteCardContentRequest req) {
        return cardService.writeContent(req.noteId(), req.cardFileName(), req.contentMd(), req.baseContentMd());
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody BatchUpdateCardsRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/update")
    public Card.CardMeta update(@RequestBody UpdateCardRequest changes) {
        return cardService.update(changes.noteId(), changes.fileName(), changes);
    }

    @PostMapping("/delete")
    public void delete(@RequestParam String noteId,
                       @RequestParam String fileName) {
        cardService.delete(noteId, fileName);
    }

}
