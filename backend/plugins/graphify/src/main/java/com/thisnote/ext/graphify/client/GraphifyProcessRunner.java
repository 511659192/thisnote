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

/**
 * 使用隔离的 Provider 环境启动 Graphify CLI，并把输出流写入后端日志。
 *
 * <p>OpenCode Go 使用本机回环代理：CLI 拿到短期本地 key，代理再注入
 * 真实 key 和必需的 session header。
 */
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
        OpenCodeGoProxy proxy = startProxy(provider, apiKey);
        try {
            ProcessBuilder builder = processBuilder(executable, arguments, workingDirectory, operation);
            String processApiKey = configureEnvironment(builder, provider, apiKey, proxy);
            return execute(builder, operation, provider, startedAt, apiKey, processApiKey);
        } catch (IOException | RuntimeException e) {
            log.error("Graphify 命令执行异常：operation={}, provider={}", operation, provider, e);
            throw e;
        } finally {
            if (proxy != null) {
                proxy.close();
                log.debug("OpenCode Go 本机代理已关闭：operation={}", operation);
            }
        }
    }

    private static OpenCodeGoProxy startProxy(String provider, String apiKey) throws IOException {
        if (!GraphifySettingsService.OPENCODE_GO_PROVIDER.equals(provider)) {
            return null;
        }
        return OpenCodeGoProxy.start(apiKey);
    }

    private static ProcessBuilder processBuilder(String executable,
                                                 List<String> arguments,
                                                 Path workingDirectory,
                                                 String operation) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        log.info("Graphify 进程启动：operation={}, executable={}, workingDirectory={}",
                operation, executable, workingDirectory);
        return new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
    }

    private static String configureEnvironment(ProcessBuilder builder,
                                               String provider,
                                               String apiKey,
                                               OpenCodeGoProxy proxy) {
        Map<String, String> environment = builder.environment();
        environment.put("GRAPHIFY_NO_AUTO_REFRESH", "1");
        GraphifySettingsService.environmentKeyNamesToClear().forEach(environment::remove);
        String processApiKey = apiKey;
        if (proxy != null) {
            environment.put("OPENAI_BASE_URL", proxy.baseUrl());
            environment.put("NO_PROXY", noProxyWithLoopback(environment.get("NO_PROXY")));
            processApiKey = proxy.localApiKey();
        }
        log.debug("Graphify 运行环境：proxy={}, keyConfigured={}",
                proxy != null, processApiKey != null && !processApiKey.isBlank());
        if (processApiKey != null && !processApiKey.isBlank()) {
            for (String name : GraphifySettingsService.environmentKeyNames(provider)) {
                environment.put(name, processApiKey);
            }
        }
        return processApiKey;
    }

    private Result execute(ProcessBuilder builder,
                           String operation,
                           String provider,
                           long startedAt,
                           String apiKey,
                           String processApiKey) throws IOException {
        Process process = startProcess(builder, operation);
        try {
            ArrayDeque<String> output = new ArrayDeque<>();
            int outputLines = readOutput(process, operation, apiKey, processApiKey, output);
            int exitCode = waitFor(process);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (exitCode == 0) {
                log.info("Graphify 命令完成：operation={}, provider={}, exitCode={}, outputLines={}, elapsedMs={}",
                        operation, provider, exitCode, outputLines, elapsedMillis);
            } else {
                log.warn("Graphify 命令失败：operation={}, provider={}, exitCode={}, outputLines={}, elapsedMs={}",
                        operation, provider, exitCode, outputLines, elapsedMillis);
            }
            return new Result(exitCode, String.join("\n", output));
        } finally {
            clearProcess(process);
        }
    }

    private Process startProcess(ProcessBuilder builder, String operation) throws IOException {
        Process process;
        synchronized (processLock) {
            if (stopped) {
                throw new IOException("Graphify 插件正在停用");
            }
            process = builder.start();
            activeProcess = process;
        }
        log.info("Graphify 子进程已启动：operation={}, pid={}", operation, process.pid());
        return process;
    }

    private int readOutput(Process process,
                           String operation,
                           String apiKey,
                           String processApiKey,
                           ArrayDeque<String> output) throws IOException {
        int outputLines = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.size() == OUTPUT_LINE_LIMIT) {
                    output.removeFirst();
                }
                String safeLine = redact(line, apiKey, processApiKey);
                output.addLast(safeLine);
                outputLines++;
                if (isImportantOutput(safeLine)) {
                    log.info("Graphify 输出[{}]：{}", operation, safeLine);
                } else {
                    log.debug("Graphify 输出[{}]：{}", operation, safeLine);
                }
            }
        }
        return outputLines;
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graphify 子进程被中断", e);
        }
    }

    private void clearProcess(Process process) {
        synchronized (processLock) {
            if (activeProcess == process) {
                activeProcess = null;
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

    private String redact(String line,
                          String... apiKeys) {
        // 输出可能包含请求头或模型错误，写日志前先脱敏。
        String safe = line;
        for (String apiKey : apiKeys) {
            if (apiKey != null && !apiKey.isBlank()) {
                safe = safe.replace(apiKey, "[redacted]");
            }
        }
        return safe.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[redacted]").replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[redacted]");
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

    private static boolean isImportantOutput(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("failed") || lower.contains("exception") || lower.contains("warn") || lower.contains("incomplete") || lower.contains(
                "timeout") || lower.contains("retry") || lower.contains("rate limit");
    }

    public record Result(int exitCode, String output) {
    }
}
