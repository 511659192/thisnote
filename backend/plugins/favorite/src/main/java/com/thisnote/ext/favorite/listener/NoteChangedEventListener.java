package com.thisnote.ext.favorite.listener;

import com.thisnote.ext.favorite.service.FavoriteService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final FavoriteService favoriteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() == NoteChangedEvent.Kind.DELETED && favoriteService.removeFavorite(change.noteId())) {
            log.info("笔记已删除，收藏记录一并清除：{}", change.noteId());
        }
    }
}
