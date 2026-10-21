package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyGraphReader;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
import com.thisnote.plugin.api.event.GraphifyTextContextFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class GraphifyTextContextFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<GraphifyTextContextFutureEvent>> {

    private final GraphifyGraphReader graphReader;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<GraphifyTextContextFutureEvent> event) {
        GraphifyTextContextFutureEvent query = event.getPayload();
        try {
            GraphifyNoteContext context = graphReader.textContext(query.text());
            query.result().complete(context);
            log.debug("Graphify 文本上下文查询完成：available={}, concepts={}, communities={}",
                    context.available(), context.concepts().size(), context.communities().size());
        } catch (Exception e) {
            log.error("Graphify 文本上下文查询失败：textLength={}",
                    query.text() == null ? 0 : query.text().length(), e);
            query.result().completeExceptionally(e);
        }
    }
}
