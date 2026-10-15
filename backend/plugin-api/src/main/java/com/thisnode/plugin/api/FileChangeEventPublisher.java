package com.thisnode.plugin.api;

import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.Objects;

/** Publishes plugin-owned file changes through the shared application context. */
public final class FileChangeEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final String sourcePluginId;

    public FileChangeEventPublisher(ApplicationEventPublisher publisher,
                                    String sourcePluginId) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
        this.sourcePluginId = sourcePluginId;
    }

    public void fileWritten(Path file,
                            boolean previouslyExisted) {
        publish(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED,
                file,
                null);
    }

    public void fileDeleted(Path file) {
        publish(FileChangeEvent.Operation.DELETED, file, null);
    }

    public void fileMoved(Path previousFile,
                          Path file) {
        publish(FileChangeEvent.Operation.UPDATED, file, previousFile);
    }

    private void publish(FileChangeEvent.Operation operation,
                         Path file,
                         Path previousFile) {
        String url = file.toAbsolutePath().normalize().toUri().toString();
        String previousUrl = previousFile == null
                ? null
                : previousFile.toAbsolutePath().normalize().toUri().toString();
        publisher.publishEvent(new FileChangeEvent(sourcePluginId, operation, url, previousUrl));
    }
}
