package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 通用 AI completion 请求，不绑定具体业务语义。
 */
public record AiCompletionFutureEvent(String systemPrompt,
                                      String userText,
                                      List<ImageInput> images,
                                      int maxTokens,
                                      CompletableFuture<String> completion)
        implements PluginFutureEvent<String> {

    public AiCompletionFutureEvent {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userText = Objects.requireNonNull(userText, "userText");
        images = images == null ? List.of() : List.copyOf(images);
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        completion = Objects.requireNonNull(completion, "completion");
    }

    public static AiCompletionFutureEvent request(String systemPrompt,
                                                  String userText,
                                                  List<ImageInput> images,
                                                  int maxTokens) {
        return new AiCompletionFutureEvent(systemPrompt, userText, images, maxTokens, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<String> result() {
        return completion;
    }

    public record ImageInput(String mediaType,
                             String base64) {

        public ImageInput {
            mediaType = Objects.requireNonNull(mediaType, "mediaType");
            base64 = Objects.requireNonNull(base64, "base64");
        }
    }
}
