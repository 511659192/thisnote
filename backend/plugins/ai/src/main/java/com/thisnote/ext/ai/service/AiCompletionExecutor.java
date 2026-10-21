package com.thisnote.ext.ai.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 所有 AI completion 共用的单线程执行器。
 *
 * <p>tags、ai-summary、ai-title 与连接测试都经这里排队，因此全局同时最多一个模型请求。
 */
@Slf4j
@Component
public class AiCompletionExecutor {

    private static final int QUEUE_CAPACITY = 32;
    private static final long CALL_TIMEOUT_SECONDS = 130;

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            task -> {
                Thread thread = new Thread(task, "thisnote-ai-completion");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicLong retryAfterUntilMillis = new AtomicLong();

    public void execute(Runnable task) {
        try {
            worker.execute(() -> {
                awaitRetryAfter();
                task.run();
            });
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("AI 任务队列已满，请稍后重试", e);
        }
    }

    public <T> T call(Callable<T> task) {
        Future<T> future;
        AtomicBoolean timedOut = new AtomicBoolean();
        try {
            future = worker.submit(() -> {
                awaitRetryAfter();
                if (timedOut.get()) {
                    return null;
                }
                return task.call();
            });
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("AI 任务队列已满，请稍后重试", e);
        }
        try {
            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 请求被中断", e);
        } catch (TimeoutException e) {
            timedOut.set(true);
            future.cancel(false);
            throw new IllegalStateException("AI 请求超时", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause == null ? "AI 请求失败" : cause.getMessage(), cause);
        }
    }

    public void deferQueuedRequests(long delayMillis, String profileId) {
        if (delayMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long resumeAt = delayMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delayMillis;
        retryAfterUntilMillis.accumulateAndGet(resumeAt, Math::max);
        log.warn("AI completion 队列按 Retry-After 冷却：profileId={}, delayMs={}, queued={}",
                profileId, delayMillis, worker.getQueue().size());
    }

    private void awaitRetryAfter() {
        while (true) {
            long remainingMillis = retryAfterUntilMillis.get() - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("AI completion Retry-After 等待被中断：remainingMs={}", remainingMillis, e);
                return;
            }
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
        log.info("AI completion 执行器已关闭");
    }
}
