package com.thisnode.ext.notes.api;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 探活端点：脚本、桌面壳与界面侧启动探测（waitForBackend）都靠它确认后端已经就绪。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemController {

    @Value("${server.port:45917}")
    private int port;

    private final long startedAt = System.currentTimeMillis();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "app", "thisnode-backend",
                "version", "0.1.0",
                "port", port,
                "pid", ProcessHandle.current().pid(),
                "uptimeMs", System.currentTimeMillis() - startedAt,
                "jvm", System.getProperty("java.version"));
    }
}
