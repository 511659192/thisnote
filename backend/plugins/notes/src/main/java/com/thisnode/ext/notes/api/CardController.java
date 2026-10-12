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
    public CardsPageDto get(@RequestParam String noteId,
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
    public Card writeContent(@RequestParam String cardFileName,
                                    @RequestBody(required = false) WriteCardContentRequest req) {
        WriteCardContentRequest request = req == null ? new WriteCardContentRequest() : req;
        return cardService.writeContent(cardFileName, request.contentMd(), request.baseContentMd());
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody BatchUpdateCardsRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/update")
    public Card.CardMeta update(@RequestParam String fileName,
                                @RequestBody UpdateCardRequest changes) {
        return cardService.update(fileName, changes);
    }

}
