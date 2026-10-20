package com.thisnote.ext.tags.listener;

import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.ext.tags.service.TagUsageRefreshService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final TagsRepository tagsRepository;
    private final TagUsageRefreshService tagUsageRefreshService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() == NoteChangedEvent.Kind.DELETED) {
            if (tagsRepository.deleteNote(change.noteId())) {
                log.info("笔记已删除，标签一并清除：{}", change.noteId());
            }
        } else if (change.kind() == NoteChangedEvent.Kind.TITLE_CHANGED || change.kind() == NoteChangedEvent.Kind.TEXT_CHANGED) {
            tagUsageRefreshService.request(change.noteId());
        }
    }
}
