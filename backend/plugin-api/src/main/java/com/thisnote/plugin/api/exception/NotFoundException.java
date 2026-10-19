package com.thisnote.plugin.api.exception;

/**
 * 请求的资源不存在（HTTP 404）。
 *
 * <p><b>为什么放在契约里：</b>统一的 JSON 错误形状由主体（平台）的
 * {@code ApiExceptionHandler} 负责渲染，而抛出异常的业务代码在插件中 —— 异常类型必须是两边同一
 * ClassLoader 能看到的类，才能跨容器被 advice 捕获。业务插件定义的具体异常继承它；主体侧只认本类型。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
