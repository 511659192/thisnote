package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.service.CardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A note's cards: their geometry, their text, and the whole-card operations on them.
 *
 * <p>Nested under the note because a card has no meaning outside the note it belongs to — there
 * is no board/container concept.
 *
 * <p><b>为什么读要带 {@code ids}：</b>卡片清单由列表接口给出（{@code /api/notes} 的
 * {@code cards}，只有身份），坐标与正文留在卡片文件里，属于页面级数据。打开笔记时按视口
 * 分批用 {@code ids} 取——不带参数则整页返回。这样列表页不必背几何，长页面也不必一次拉全。
 */
@RestController
@RequestMapping("/api/notes/{noteId}/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /** 这几张卡（{@code ids} 省略 = 整页）：卡片几何 + 它自己的正文。 */
    @GetMapping
    public Dtos.CardsDto get(@PathVariable String noteId,
                                  @RequestParam(required = false) List<String> ids) {
        return cardService.page(noteId, ids);
    }

    @PostMapping
    public Dtos.CardView create(@PathVariable String noteId,
                                     @RequestBody(required = false) Dtos.CreateCardRequest req) {
        return cardService.create(noteId, req);
    }

    /** Write markdown typed inside a card back into the note. */
    @PostMapping("/{cardId}/content")
    public Dtos.CardView writeContent(@PathVariable String noteId, @PathVariable String cardId,
                                           @RequestBody(required = false) Dtos.WriteContentRequest req) {
        return cardService.writeContent(cardId,
                req == null || req.contentMd() == null ? "" : req.contentMd(),
                req == null ? null : req.baseContentMd());
    }

    @PostMapping("/patch")
    public Map<String, Object> batchUpdate(@PathVariable String noteId,
                                           @RequestBody Dtos.BatchCardPatchRequest req) {
        cardService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PostMapping("/{cardId}/patch")
    public Dtos.CardDto update(@PathVariable String noteId, @PathVariable String cardId,
                                    @RequestBody Dtos.CardPatch patch) {
        return cardService.update(cardId, patch);
    }

    @PostMapping("/{cardId}/duplicate")
    public Dtos.CardDto duplicate(@PathVariable String noteId, @PathVariable String cardId,
                                       @RequestParam(defaultValue = "28") double dx,
                                       @RequestParam(defaultValue = "28") double dy) {
        return cardService.duplicate(cardId, dx, dy);
    }

    @PostMapping("/z")
    public Dtos.CardDto zOrder(@PathVariable String noteId, @RequestBody Map<String, String> body) {
        return cardService.zOrder(body.getOrDefault("id", ""), body.getOrDefault("op", "top"));
    }

    @PostMapping("/{cardId}/delete")
    public Map<String, Object> delete(@PathVariable String noteId, @PathVariable String cardId,
                                      @RequestParam(defaultValue = "false") boolean deleteContent) {
        cardService.delete(cardId, deleteContent);
        return Map.of("ok", true);
    }
}
