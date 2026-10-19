package com.thisnote.ext.graphify.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

final class OpenCodeGoProxy implements AutoCloseable {

    private static final String UPSTREAM_BASE_URL = "https://opencode.ai/inference/go/openai/v1";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
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
        try {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/v1/chat/completions".equals(exchange.getRequestURI().getRawPath())) {
                writeResponse(exchange, 404, "{\"error\":{\"message\":\"Unsupported OpenCode Go request\"}}",
                        "application/json");
                return;
            }
            if (!("Bearer " + localApiKey).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                writeResponse(exchange, 401, "{\"error\":{\"message\":\"Unauthorized local request\"}}",
                        "application/json");
                return;
            }

            String query = exchange.getRequestURI().getRawQuery();
            URI target = URI.create(UPSTREAM_BASE_URL + "/chat/completions"
                    + (query == null ? "" : "?" + query));
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            HttpRequest request = HttpRequest.newBuilder(target)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", contentType == null ? "application/json" : contentType)
                    .header("User-Agent", "thisnote-graphify/1.0")
                    .header("x-opencode-session", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            response.headers().firstValue("retry-after")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Retry-After", value));
            writeResponse(exchange, response.statusCode(), response.body(),
                    response.headers().firstValue("content-type").orElse("application/json"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeResponse(exchange, 502, "{\"error\":{\"message\":\"OpenCode Go request interrupted\"}}",
                    "application/json");
        } catch (IOException e) {
            writeResponse(exchange, 502, "{\"error\":{\"message\":\"OpenCode Go request failed\"}}",
                    "application/json");
        } finally {
            exchange.close();
        }
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
    }
}
