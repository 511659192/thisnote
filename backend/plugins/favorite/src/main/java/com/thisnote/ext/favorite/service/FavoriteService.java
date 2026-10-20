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
        return favoriteRepository.getAllFavoriteNoteIds();
    }

    public boolean isFavorite(String noteId) {
        return favoriteRepository.isFavorite(noteId);
    }

    public void setFavorite(String noteId, boolean favorite) {
        favoriteRepository.updateFavorite(noteId, favorite);
        log.info("笔记 {} 收藏 → {}", noteId, favorite);
    }

}
