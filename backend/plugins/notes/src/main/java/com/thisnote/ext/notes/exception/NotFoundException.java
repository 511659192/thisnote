package com.thisnote.ext.notes.exception;

/** A note-domain resource requested by the client does not exist. */
public class NotFoundException extends com.thisnote.plugin.api.exception.NotFoundException {

    public NotFoundException(String resource) {
        super("未找到：" + resource);
    }
}
