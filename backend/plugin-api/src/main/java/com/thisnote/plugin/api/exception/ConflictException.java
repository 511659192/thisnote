package com.thisnote.plugin.api.exception;

/**
 * 请求与当前状态冲突（HTTP 409；重命名撞车、引用未清空等）。
 *
 * <p>放在契约里的原因见 {@link NotFoundException}：异常要穿过「核心插件 → 主体平台」
 * 的容器边界被统一渲染，类型必须双方可见。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
