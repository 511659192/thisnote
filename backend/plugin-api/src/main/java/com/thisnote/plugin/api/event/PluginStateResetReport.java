package com.thisnote.plugin.api.event;

import java.util.List;

/**
 * Aggregated result of resetting every installed plugin.
 */
public record PluginStateResetReport(boolean ok, List<PluginResetResult> results) {

    public PluginStateResetReport {
        results = results == null ? List.of() : List.copyOf(results);
        ok = results.stream().noneMatch(result -> result.status() == PluginResetResult.Status.FAILED);
    }
}
