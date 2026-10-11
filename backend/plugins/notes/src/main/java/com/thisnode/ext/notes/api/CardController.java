package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.BatchUpdateCardsRequest;
import com.thisnode.ext.notes.api.dto.CardViewDto;
import com.thisnode.ext.notes.api.dto.CardsPageDto;
import com.thisnode.ext.notes.api.dto.CreateCardRequest;
import com.thisnode.ext.notes.api.dto.UpdateCardRequest;
import com.thisnode.ext.notes.api.dto.WriteCardContentRequest;
import com.thisnode.ext.notes.domain.Card;
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
    public CardsPageDto get(@RequestParam String noteId,
                                  @RequestParam(required = false) List<String> ids) {
        return cardService.page(noteId, ids);
    }

    @PostMapping("/create")
    public CardViewDto create(@RequestParam String noteId,
                                     @RequestBody(required = false) CreateCardRequest req) {
        return cardService.create(noteId, req == null ? new CreateCardRequest() : req);
    }

    /** Write markdown typed inside a card back into the note. */
    @PostMapping("/content")
    public CardViewDto writeContent(@RequestParam String cardId,
                                           @RequestBody(required = false) WriteCardContentRequest req) {
        WriteCardContentRequest request = req == null ? new WriteCardContentRequest() : req;
        return cardService.writeContent(cardId, request.contentMd(), request.baseContentMd());
    }

    @PostMapping("/batch-update")
    public Map<String, Object> batchUpdate(@RequestBody BatchUpdateCardsRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/update")
    public Card.CardMeta update(@RequestParam String cardId, @RequestBody UpdateCardRequest changes) {
        return cardService.update(cardId, changes);
    }

}
