package com.thisnote.ext.favorite.service;

import com.thisnote.ext.favorite.repository.FavoriteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;

    public FavoriteService(FavoriteRepository favoriteRepository) {
        this.favoriteRepository = favoriteRepository;
    }

    public List<String> favoriteNoteIds() {
        return favoriteRepository.favoriteNoteIds();
    }

    public boolean isFavorite(String noteId) {
        return favoriteRepository.isFavorite(noteId);
    }

    public void setFavorite(String noteId, boolean favorite) {
        favoriteRepository.setFavorite(noteId, favorite);
        log.info("笔记 {} 收藏 → {}", noteId, favorite);
    }

    public boolean removeFavorite(String noteId) {
        boolean removed = favoriteRepository.removeFavorite(noteId);
        log.info("笔记 {} 取消收藏（原收藏={}）", noteId, removed);
        return removed;
    }

    /** 状态重置：清空全部收藏。 */
    public void clear() {
        favoriteRepository.clear();
        log.info("收藏状态已清空");
    }
}
