package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginNotification;

/**
 * Memory-cycle enablement changed for a card.
 *
 * <p>Publish through the main application context and register cross-plugin listeners there.
 */
public record MemoryCycleStateChangedEvent(String cardId, boolean enabled) implements PluginNotification {
}
