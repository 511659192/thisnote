package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.service.NoteBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/notes/{noteId}/blocks")
@RequiredArgsConstructor
public class NoteBlocksController {

    private final NoteBlockService blockService;

    /** 这几张卡（{@code ids} 省略 = 整页）：卡片几何 + 它自己的正文。 */
    @GetMapping
    public Dtos.NoteBlocksDto get(@PathVariable String noteId,
                                  @RequestParam(required = false) List<String> ids) {
        return blockService.page(noteId, ids);
    }

    @PostMapping
    public Dtos.NoteBlockView create(@PathVariable String noteId,
                                     @RequestBody(required = false) Dtos.CreateBlockRequest req) {
        return blockService.create(noteId, req);
    }

    /** Write markdown typed inside a card back into the note. */
    @PatchMapping("/{blockId}/content")
    public Dtos.NoteBlockView writeContent(@PathVariable String noteId, @PathVariable String blockId,
                                           @RequestBody(required = false) Dtos.WriteContentRequest req) {
        return blockService.writeContent(blockId,
                req == null || req.contentMd() == null ? "" : req.contentMd(),
                req == null ? null : req.baseContentMd());
    }

    @PatchMapping
    public Map<String, Object> batchUpdate(@PathVariable String noteId,
                                           @RequestBody Dtos.BatchBlockPatchRequest req) {
        blockService.batchUpdate(req);
        return Map.of("ok", true);
    }

    @PatchMapping("/{blockId}")
    public Dtos.NoteBlockDto update(@PathVariable String noteId, @PathVariable String blockId,
                                    @RequestBody Dtos.BlockPatch patch) {
        return blockService.update(blockId, patch);
    }

    @PostMapping("/{blockId}/duplicate")
    public Dtos.NoteBlockDto duplicate(@PathVariable String noteId, @PathVariable String blockId,
                                       @RequestParam(defaultValue = "28") double dx,
                                       @RequestParam(defaultValue = "28") double dy) {
        return blockService.duplicate(blockId, dx, dy);
    }

    @PostMapping("/z")
    public Dtos.NoteBlockDto zOrder(@PathVariable String noteId, @RequestBody Map<String, String> body) {
        return blockService.zOrder(body.getOrDefault("id", ""), body.getOrDefault("op", "top"));
    }

    @DeleteMapping("/{blockId}")
    public Map<String, Object> delete(@PathVariable String noteId, @PathVariable String blockId,
                                      @RequestParam(defaultValue = "false") boolean deleteContent) {
        blockService.delete(blockId, deleteContent);
        return Map.of("ok", true);
    }
}
