package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyGraphReader;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
import com.thisnote.plugin.api.event.GraphifyNoteContextFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class GraphifyNoteContextFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<GraphifyNoteContextFutureEvent>> {

    private final GraphifyGraphReader graphReader;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<GraphifyNoteContextFutureEvent> event) {
        GraphifyNoteContextFutureEvent query = event.getPayload();
        try {
            GraphifyNoteContext context = graphReader.noteContext(query.noteId());
            query.result().complete(context);
            log.debug("Graphify 笔记上下文查询完成：noteId={}, available={}, concepts={}, related={}",
                    query.noteId(), context.available(), context.concepts().size(), context.relatedNotes().size());
        } catch (Exception e) {
            log.error("Graphify 笔记上下文查询失败：noteId={}", query.noteId(), e);
            query.result().completeExceptionally(e);
        }
    }
}
