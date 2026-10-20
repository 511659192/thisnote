package com.thisnote.ext.graphify.client;

import com.thisnote.ext.graphify.service.GraphifySettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class GraphifyProcessRunner {

    private static final int OUTPUT_LINE_LIMIT = 80;
    private final Object processLock = new Object();
    private Process activeProcess;
    private boolean stopped;

    public Result run(String executable,
                      List<String> arguments,
                      Path workingDirectory,
                      String provider,
                      String apiKey) throws IOException {
        String operation = arguments.isEmpty() ? "unknown" : arguments.get(0);
        long startedAt = System.nanoTime();
        log.info("Graphify 命令开始：operation={}, provider={}", operation, provider);
        OpenCodeGoProxy proxy = null;
        try {
            if (GraphifySettingsService.OPENCODE_GO_PROVIDER.equals(provider)) {
                proxy = OpenCodeGoProxy.start(apiKey);
            }

            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("GRAPHIFY_NO_AUTO_REFRESH", "1");
            GraphifySettingsService.environmentKeyNamesToClear().forEach(environment::remove);
            String processApiKey = apiKey;
            if (proxy != null) {
                environment.put("OPENAI_BASE_URL", proxy.baseUrl());
                environment.put("NO_PROXY", noProxyWithLoopback(environment.get("NO_PROXY")));
                processApiKey = proxy.localApiKey();
            }
            if (processApiKey != null && !processApiKey.isBlank()) {
                for (String name : GraphifySettingsService.environmentKeyNames(provider)) {
                    environment.put(name, processApiKey);
                }
            }

            Process process;
            synchronized (processLock) {
                if (stopped) {
                    throw new IOException("Graphify 插件正在停用");
                }
                process = builder.start();
                activeProcess = process;
            }
            ArrayDeque<String> output = new ArrayDeque<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.size() == OUTPUT_LINE_LIMIT) {
                        output.removeFirst();
                    }
                    output.addLast(redact(line, apiKey, processApiKey));
                }
                int exitCode = process.waitFor();
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                if (exitCode == 0) {
                    log.info("Graphify 命令完成：operation={}, provider={}, exitCode={}, elapsedMs={}",
                            operation, provider, exitCode, elapsedMillis);
                } else {
                    log.warn("Graphify 命令失败：operation={}, provider={}, exitCode={}, elapsedMs={}",
                            operation, provider, exitCode, elapsedMillis);
                }
                return new Result(exitCode, String.join("\n", output));
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Graphify 子进程被中断", e);
            } finally {
                synchronized (processLock) {
                    if (activeProcess == process) {
                        activeProcess = null;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw e;
        } finally {
            if (proxy != null) {
                proxy.close();
            }
        }
    }

    public void stop() {
        Process process;
        synchronized (processLock) {
            stopped = true;
            process = activeProcess;
            activeProcess = null;
        }
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            log.error("等待 Graphify 子进程退出被中断", e);
        }
        log.info("Graphify 子进程已停止");
    }

    private String redact(String line, String... apiKeys) {
        String safe = line;
        for (String apiKey : apiKeys) {
            if (apiKey != null && !apiKey.isBlank()) {
                safe = safe.replace(apiKey, "[redacted]");
            }
        }
        return safe.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[redacted]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[redacted]");
    }

    private static String noProxyWithLoopback(String existing) {
        String value = existing == null ? "" : existing;
        String lower = value.toLowerCase();
        if (!lower.contains("127.0.0.1")) {
            value = value.isBlank() ? "127.0.0.1" : value + ",127.0.0.1";
            lower = value.toLowerCase();
        }
        if (!lower.contains("localhost")) {
            value = value.isBlank() ? "localhost" : value + ",localhost";
        }
        return value;
    }

    public record Result(int exitCode, String output) {
    }
}
