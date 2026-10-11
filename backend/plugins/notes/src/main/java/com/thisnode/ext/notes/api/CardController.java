package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
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

    @PostMapping("/update")
    public Card.CardMeta update(@RequestParam String cardId, @RequestBody Dtos.CardUpdate changes) {
        return cardService.update(cardId, changes);
    }

}
