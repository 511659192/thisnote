package com.thisnode.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台自己的探活端点：脚本、桌面壳与界面侧启动探测（{@code waitForBackend}）都靠它确认后端已经就绪。
 *
 * <p>它以前在 notes 插件里（{@code com.thisnode.ext.notes.api.SystemController}）：返回的字段
 * ({@code pid}/{@code port}/{@code jvm}/{@code uptimeMs}…) 全是平台自己的信息，却要等业务插件的
 * Spring 子容器起来才答得上 —— 平台探活因此绑在插件上。移到平台侧后，Tomcat 一开始接流量就能答。
 *
 * <p>路径与响应字段保持与原实现一致，调用方（界面侧 {@code waitForBackend}、脚本、桌面壳）零改动。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

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
