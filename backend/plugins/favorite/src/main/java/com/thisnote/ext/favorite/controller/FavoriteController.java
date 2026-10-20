package com.thisnote.ext.favorite.controller;

import com.thisnote.ext.favorite.FavoritePlugin;
import com.thisnote.ext.favorite.model.request.FavoriteBatchRequest;
import com.thisnote.ext.favorite.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 收藏插件自己的端点：{@code /api/plugins/favorite/…}。
 *
 * <p>收藏状态由本插件自己的文件与端点管理；这里只回 id，不读取笔记内容，因此不依赖核心插件。
 */
@RestController
@RequestMapping("/api/plugins/" + FavoritePlugin.PLUGIN_ID)
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 收藏了哪些笔记 —— **只给 id**。
     *
     * <p>标题与预览不在这里拼：本插件不读笔记数据（因此不依赖核心插件），调用方拿这份 id 列表
     * 与它已有的笔记列表做交集即可。
     */
    @GetMapping("/get-favorites")
    public List<Map<String, Object>> favoriteNoteIds() {
        return favoriteService.favoriteNoteIds().stream()
                .map(id -> Map.<String, Object>of("id", id))
                .toList();
    }

    @GetMapping("/get-state")
    public Map<String, Boolean> state(@RequestParam String noteId) {
        return Map.of("favorite", favoriteService.isFavorite(noteId));
    }

    @PostMapping("/update-state")
    public Map<String, Boolean> setState(@RequestParam String noteId,
                                         @RequestParam boolean value) {
        favoriteService.setFavorite(noteId, value);
        return Map.of("favorite", value);
    }

    /**
     * 批量设置收藏：给「按笔记本收藏」用。
     */
    @PostMapping("/update-state-batch")
    public Map<String, Object> setStateBatch(@RequestBody FavoriteBatchRequest request) {
        int updated = 0;
        for (String noteId : request.noteIds()) {
            if (noteId == null || noteId.isBlank()) continue;
            favoriteService.setFavorite(noteId, request.value());
            updated++;
        }
        return Map.of("updated", updated);
    }
}
