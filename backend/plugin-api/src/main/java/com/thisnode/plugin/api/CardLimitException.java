package com.thisnode.plugin.api;

/**
 * 达到每块画布的卡片上限（HTTP 409，附 {@code limit} 供界面侧提示）。
 *
 * <p>放在契约里的原因见 {@link NotFoundException}：异常由核心插件（notes）抛出、
 * 由主体平台的统一错误渲染捕获，类型必须双方可见。
 */
public class CardLimitException extends RuntimeException {

    private final int limit;

    public CardLimitException(String message, int limit) {
        super(message);
        this.limit = limit;
    }

    /** 当前生效的卡片上限。 */
    public int limit() {
        return limit;
    }
}
