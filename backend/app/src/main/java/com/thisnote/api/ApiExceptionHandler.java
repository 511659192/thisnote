package com.thisnote.api;

import com.thisnote.plugin.api.exception.CardLimitException;
import com.thisnote.plugin.api.exception.ConflictException;
import com.thisnote.plugin.api.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台侧的统一 JSON 错误渲染 —— 调用方据此弹 toast，而不是看到堆栈。
 *
 * <p><b>为什么在主体（平台）而不是核心插件：</b>插件控制器由 SBP 注册进主体的
 * {@code RequestMappingHandlerMapping}，但 {@code @RestControllerAdvice} 只会从**主体容器**
 * 收集 —— advice 必须留在这边才能兜住插件里抛出的异常。它认的是 plugin-api 的契约异常类型
 * （{@link NotFoundException} / {@link ConflictException} / {@link CardLimitException}）：
 * 这些类由主体类加载器装载、各插件经委派共享同一个 Class 对象，跨容器 instanceof 成立。
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        log.error("API not found", e);
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        log.error("API conflict", e);
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Card-cap violations are a normal user-facing condition, not a server error: 409 with a
     * message the page shows verbatim.
     */
    @ExceptionHandler(CardLimitException.class)
    public ResponseEntity<Map<String, Object>> cardLimit(CardLimitException e) {
        log.error("API block limit", e);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", HttpStatus.CONFLICT.value());
        m.put("error", "BLOCK_LIMIT");
        m.put("message", e.getMessage());
        m.put("limit", e.limit());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        log.error("API bad request", e);
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 请求参数缺失/类型不符（如插件端点缺 ids 参数）：客户端可纠正的 400，仍按统一异常日志记录。 */
    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> badBinding(org.springframework.web.bind.ServletRequestBindingException e) {
        log.error("API request binding error", e);
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** "资源在，但状态不对"（如给未开启记忆周期的卡片评分）：客户端可自行纠正。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        log.error("API state conflict", e);
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        log.error("API forbidden", e);
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 路径没有对应处理器（写错了，或者端点已被移除）就该是 404。
     *
     * <p>放过它的话会被下面的 {@code Exception} 兜底成 500 —— 状态码撒谎。单体交付之后这一点
     * 更要紧：静态资源兜底与错误派发都挂在同一套 MVC 上。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        log.error("API route not found", e);
        return body(HttpStatus.NOT_FOUND, "接口不存在");
    }

    /**
     * 路径在、方法不对（例如对一个只有 PATCH 的端点发 PUT）该是 405，不是 500。
     *
     * <p>它和上一条是同一类问题：{@code RequestMappingInfoHandlerMapping} 找不到匹配的方法时
     * 抛这个异常，落到 {@code Exception} 兜底就变成 500 —— 状态码撒谎。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.error("API method not allowed", e);
        return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("error", status.getReasonPhrase());
        m.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(m);
    }
}
