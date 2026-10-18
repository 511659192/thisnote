package com.thisnode.ext.memorycycle.model.request;

import java.util.List;

public record CardStateBatchRequest(List<String> cardIds, boolean value) {
}
