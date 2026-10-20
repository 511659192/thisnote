package com.thisnote.ext.aisummary.listener;

import com.thisnote.ext.aisummary.service.AiSummaryService;
import com.thisnote.plugin.api.event.GraphifyIndexCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class GraphifyIndexCompletedEventListener
        implements ApplicationListener<PayloadApplicationEvent<GraphifyIndexCompletedEvent>> {

    private final AiSummaryService summaryService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<GraphifyIndexCompletedEvent> event) {
        try {
            summaryService.invalidateAll();
            log.info("Graphify 图谱更新已触发 AI 总结失效：completedAt={}",
                    event.getPayload().completedAt());
        } catch (Exception e) {
            log.error("Graphify 图谱更新后失效 AI 总结失败：completedAt={}",
                    event.getPayload().completedAt(), e);
        }
    }
}
