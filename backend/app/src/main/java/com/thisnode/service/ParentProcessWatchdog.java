package com.thisnode.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;

/**
 * 宿主退出时让本进程跟着收摊。
 *
 * <p>宿主把本进程的 stdin 接到管道上：宿主退出（含被强杀、不发关闭事件）时管道关闭，
 * 下面这次读取返回 {@code -1}，后端自己结束，而不是变成孤儿进程占着数据库与端口。
 *
 * <p>默认关闭（{@code thisnode.exit-on-stdin-close}）：在终端里直接 {@code java -jar} 时
 * stdin 是 TTY，也可能报 EOF —— 默认关闭就不会被误触发。
 */
@Component
public class ParentProcessWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ParentProcessWatchdog.class);

    private final ApplicationContext context;

    @Value("${thisnode.exit-on-stdin-close:false}")
    private boolean enabled;

    public ParentProcessWatchdog(ApplicationContext context) {
        this.context = context;
    }

    @PostConstruct
    public void watch() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(this::awaitParentExit, "parent-watchdog");
        thread.setDaemon(true);
        thread.start();
        log.info("parent watchdog active: will exit when stdin closes");
    }

    private void awaitParentExit() {
        try (InputStream in = System.in) {
            byte[] buffer = new byte[256];
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    log.info("stdin closed (desktop shell exited); shutting down");
                    break;
                }
            }
        } catch (IOException e) {
            log.info("stdin unavailable ({}); shutting down", e.getMessage());
        }
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }
}
