package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginNotification;

/**
 * Notes-owned attachment lifecycle notification.
 */
public record AttachmentChangedEvent(Operation operation, String fileName, String previousFileName)
        implements PluginNotification {

    public AttachmentChangedEvent {
        if (operation == null) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        if (operation == Operation.RENAME && (previousFileName == null || previousFileName.isBlank())) {
            throw new IllegalArgumentException("重命名事件必须包含 previousFileName");
        }
    }

    public static AttachmentChangedEvent created(String fileName) {
        return new AttachmentChangedEvent(Operation.CREATED, fileName, null);
    }

    public static AttachmentChangedEvent updated(String fileName) {
        return new AttachmentChangedEvent(Operation.UPDATED, fileName, null);
    }

    public static AttachmentChangedEvent deleted(String fileName) {
        return new AttachmentChangedEvent(Operation.DELETED, fileName, null);
    }

    public static AttachmentChangedEvent renamed(String previousFileName,
                                                 String fileName) {
        return new AttachmentChangedEvent(Operation.RENAME, fileName, previousFileName);
    }

    public enum Operation {
        CREATED, UPDATED, DELETED, RENAME
    }
}
