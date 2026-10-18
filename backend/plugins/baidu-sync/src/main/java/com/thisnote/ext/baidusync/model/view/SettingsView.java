package com.thisnote.ext.baidusync.model.view;

public record SettingsView(String appName,
                           boolean hasAppKey,
                           boolean hasSecretKey,
                           boolean hasSignKey,
                           boolean authorized,
                           boolean autoSync,
                           String notesRoot,
                           String remoteDirectory) {
}
