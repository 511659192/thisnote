package com.thisnote.ext.notes.listener;

import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import com.thisnote.plugin.api.event.FileChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.nio.file.Path;

@RequiredArgsConstructor
@Slf4j
public class FileChangeEventListener
        implements ApplicationListener<PayloadApplicationEvent<FileChangeEvent>> {

    private final Path attachmentsDirectory;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<FileChangeEvent> event) {
        FileChangeEvent change = event.getPayload();
        Path current = normalizedWithin(change.url(), attachmentsDirectory);
        Path previous = change.previousUrl() == null
                ? null
                : normalizedWithin(change.previousUrl(), attachmentsDirectory);
        AttachmentChangedEvent attachmentEvent;
        if (change.operation() == FileChangeEvent.Operation.RENAME && current != null && previous != null) {
            attachmentEvent = AttachmentChangedEvent.renamed(
                    previous.getFileName().toString(),
                    current.getFileName().toString());
        } else if (current != null) {
            attachmentEvent = switch (change.operation()) {
                case CREATED -> AttachmentChangedEvent.created(current.getFileName().toString());
                case UPDATED -> AttachmentChangedEvent.updated(current.getFileName().toString());
                case DELETED, RENAME -> AttachmentChangedEvent.deleted(current.getFileName().toString());
            };
        } else if (previous != null) {
            attachmentEvent = AttachmentChangedEvent.deleted(previous.getFileName().toString());
        } else {
            log.debug("忽略附件目录外的文件变更：operation={}, file={}", change.operation(), change.url());
            return;
        }
        eventPublisher.publishEvent(attachmentEvent);
        log.debug("已发布附件变更：operation={}, fileName={}, previousFileName={}",
                attachmentEvent.operation(), attachmentEvent.fileName(), attachmentEvent.previousFileName());
    }

    private static Path normalizedWithin(Path path,
                                         Path directory) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedDirectory) && !normalized.equals(normalizedDirectory)
                ? normalized
                : null;
    }
}
