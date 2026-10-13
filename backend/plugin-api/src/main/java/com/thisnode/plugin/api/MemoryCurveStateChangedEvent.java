package com.thisnode.plugin.api;

/**
 * Memory-curve enablement changed for a card.
 *
 * <p>Publish through the main application context and register cross-plugin listeners there.
 */
public record MemoryCurveStateChangedEvent(String cardId, boolean enabled) {
}
