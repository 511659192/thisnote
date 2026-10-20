package com.thisnote.plugin.api.event;

/**
 * AI profile 的非敏感摘要。API key 和执行时配置不通过该契约暴露。
 */
public record AiProfileSummary(String id,
                               String label,
                               String provider,
                               String model,
                               boolean keyConfigured) {

    public AiProfileSummary {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
    }
}
