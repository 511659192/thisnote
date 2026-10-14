package com.thisnode.ext.favorite.service;

import com.thisnode.ext.favorite.repository.FavoriteRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
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
    }

    public boolean removeFavorite(String noteId) {
        return favoriteRepository.removeFavorite(noteId);
    }
}
