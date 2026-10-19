package com.thisnote.plugin.api.event;

/**
 * Result reported by one plugin during a global state reset.
 */
public record PluginResetResult(String pluginId, Status status, String message) {

    public PluginResetResult {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        message = message == null ? "" : message;
    }

    public static PluginResetResult reset(String pluginId, String message) {
        return new PluginResetResult(pluginId, Status.RESET, message);
    }

    public static PluginResetResult skipped(String pluginId, String message) {
        return new PluginResetResult(pluginId, Status.SKIPPED, message);
    }

    public static PluginResetResult failed(String pluginId, String message) {
        return new PluginResetResult(pluginId, Status.FAILED, message);
    }

    public static PluginResetResult failed(String pluginId, Throwable error) {
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null || error.getMessage().isBlank() ? "" : "：" + error.getMessage());
        return failed(pluginId, message);
    }

    public enum Status {
        RESET,
        SKIPPED,
        FAILED
    }
}
