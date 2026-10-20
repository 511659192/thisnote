package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class AttachmentChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentChangedEvent>> {

    private static final Pattern SAFE_IMAGE_NAME = Pattern.compile("(?i)[^/\\\\]+\\.(?:png|jpg|webp)");

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentChangedEvent> event) {
        if (!indexService.shouldObserveChanges()) {
            return;
        }
        AttachmentChangedEvent change = event.getPayload();
        Set<String> fileNames = new LinkedHashSet<>();
        fileNames.add(change.fileName());
        if (change.previousFileName() != null) {
            fileNames.add(change.previousFileName());
        }
        fileNames.removeIf(fileName -> !SAFE_IMAGE_NAME.matcher(fileName).matches());
        if (!fileNames.isEmpty()) {
            indexService.queueNotesForAttachments(fileNames);
        }
    }
}
