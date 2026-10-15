package com.thisnode.plugin.api.lifecycle;

/**
 * 插件的自我体检结果。
 *
 * <p>刻意<b>不</b>在这里发起网络请求：管理页每次刷新都调它，不能因为某个插件连不上外网就卡住
 * 整个页面。插件只汇报「本地看得出来」的状态（有没有配密钥、有没有令牌、表在不在），
 * 需要真实连通性时由用户显式点「测试连接」，那是另一个动作。
 *
 * @param status  ok / degraded / error / unknown
 * @param message 一句话说明，给用户看
 */
public record PluginHealth(String status, String message) {

    public static PluginHealth ok(String message) {
        return new PluginHealth("ok", message);
    }

    public static PluginHealth degraded(String message) {
        return new PluginHealth("degraded", message);
    }

    public static PluginHealth error(String message) {
        return new PluginHealth("error", message);
    }

    public static PluginHealth unknown(String message) {
        return new PluginHealth("unknown", message);
    }
}
