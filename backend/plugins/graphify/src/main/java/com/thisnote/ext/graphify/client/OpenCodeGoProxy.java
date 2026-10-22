package com.thisnote.ext.graphify.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Graphify CLI 的通用 OpenAI 客户端与 OpenCode Go 专用会话要求之间的本机回环适配器。
 */
@Slf4j
final class OpenCodeGoProxy
        implements AutoCloseable {

    private static final String UPSTREAM_BASE_URL = "https://opencode.ai/inference/go/openai/v1";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final HttpServer server;
    private final ExecutorService executor;
    private final String apiKey;
    private final String localApiKey = UUID.randomUUID().toString();
    private final String sessionId = "ses_" + UUID.randomUUID();

    private OpenCodeGoProxy(String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("OpenCode Go API key is not configured");
        }
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", this::forward);
        server.start();
        log.info("OpenCode Go 本机代理已启动：port={}", server.getAddress().getPort());
    }

    static OpenCodeGoProxy start(String apiKey) throws IOException {
        return new OpenCodeGoProxy(apiKey);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    String localApiKey() {
        return localApiKey;
    }

    private void forward(HttpExchange exchange) throws IOException {
        long startedAt = System.nanoTime();
        try {
            if (!validate(exchange)) {
                return;
            }
            HttpResponse<byte[]> response = HTTP_CLIENT.send(
                    upstreamRequest(exchange), HttpResponse.BodyHandlers.ofByteArray());
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);
            if (response.statusCode() >= 400) {
                log.warn("OpenCode Go 代理转发返回错误：status={}, responseBytes={}, elapsedMs={}",
                        response.statusCode(), response.body() == null ? 0 : response.body().length, elapsedMillis);
            } else {
                log.info("OpenCode Go 代理转发完成：status={}, responseBytes={}, elapsedMs={}",
                        response.statusCode(), response.body() == null ? 0 : response.body().length, elapsedMillis);
            }
            response.headers().firstValue("retry-after")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Retry-After", value));
            writeResponse(exchange, response.statusCode(), response.body(),
                    response.headers().firstValue("content-type").orElse("application/json"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("OpenCode Go request interrupted", e);
            writeResponse(exchange, 502, "{\"error\":{\"message\":\"OpenCode Go request interrupted\"}}", "application/json");
        } catch (IOException e) {
            log.error("OpenCode Go request failed", e);
            writeResponse(exchange, 502, "{\"error\":{\"message\":\"OpenCode Go request failed\"}}", "application/json");
        } finally {
            exchange.close();
        }
    }

    private boolean validate(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())
                || !"/v1/chat/completions".equals(exchange.getRequestURI().getRawPath())) {
            log.warn("OpenCode Go 代理拒绝不支持的请求：method={}, path={}",
                    exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
            writeResponse(exchange, 404,
                    "{\"error\":{\"message\":\"Unsupported OpenCode Go request\"}}", "application/json");
            return false;
        }
        if (!("Bearer " + localApiKey).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            log.warn("OpenCode Go 代理拒绝未授权请求");
            writeResponse(exchange, 401,
                    "{\"error\":{\"message\":\"Unauthorized local request\"}}", "application/json");
            return false;
        }
        return true;
    }

    private HttpRequest upstreamRequest(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create(UPSTREAM_BASE_URL + "/chat/completions"
                + (query == null ? "" : "?" + query));
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        log.debug("OpenCode Go 代理转发开始：method={}, path={}, bodyBytes={}, contentType={}, query={}",
                exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                requestBody.length, contentType, query);
        return HttpRequest.newBuilder(target)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", contentType == null ? "application/json" : contentType)
                .header("User-Agent", "thisnote-graphify/1.0")
                .header("x-opencode-session", sessionId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
    }

    private static void writeResponse(HttpExchange exchange,
                                      int status,
                                      String body,
                                      String contentType) throws IOException {
        writeResponse(exchange, status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), contentType);
    }

    private static void writeResponse(HttpExchange exchange,
                                      int status,
                                      byte[] body,
                                      String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        log.info("OpenCode Go 本机代理已关闭");
    }
}
