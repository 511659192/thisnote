package com.thisnode.ext.favorite;

import com.thisnode.plugin.api.NoteChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class FavoriteNoteCleanupListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(FavoriteNoteCleanupListener.class);

    private final FavoriteService favoriteService;

    public FavoriteNoteCleanupListener(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() == NoteChangedEvent.Kind.DELETED && favoriteService.removeFavorite(change.noteId())) {
            log.info("笔记已删除，收藏记录一并清除：{}", change.noteId());
        }
    }
}
