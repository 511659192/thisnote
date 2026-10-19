package com.thisnote.plugin.api.lifecycle;

import org.pf4j.ExtensionPoint;

/**
 * 插件向平台提供的管理页与生命周期信息。领域能力可以通过 plugin-api 中的专用扩展点提供。
 *
 * <p>本平台的插件契约几乎全是 PF4J 原生机制：插件是 {@code plugins/*.jar}，身份/版本/provider
 * 来自 {@code plugin.properties}（PF4J 的 {@code PropertiesPluginDescriptorFinder}），契约版本用
 * PF4J 原生的 {@code plugin.requires} + {@code VersionManager} 校验，能力由插件里的
 * {@code @Extension} 类贡献 —— 插件作者只需要实现本接口并按需覆写；静态信息优先来自插件清单。
 *
 * <p>写成插件容器里的 bean（{@code @Extension @Component}）：SBP 的扩展工厂优先从容器取 bean，
 * 于是扩展类也能注入插件自己的服务。
 */
public interface PluginRegister extends ExtensionPoint {

    /**
     * 本契约的版本。
     *
     * <p>插件在 {@code plugin.properties} 里用 PF4J 原生的 {@code plugin.requires} 声明自己要求的
     * 版本（支持 semver 范围，例如 {@code >=3.0.0}），平台拿这个常量交给 PF4J 的
     * {@code VersionManager} 校验 —— 版本机制完全走框架，没有自定义的 apiVersion 字段。
     *
     * <p>只有明确要求时才修改它：不随公开契约的改动自动递增。
     */
    String CONTRACT_VERSION = "0.0.1";

    /** 自报健康；不覆写即「运行正常」。 */
    default PluginHealth health() {
        return PluginHealth.ok("运行正常");
    }

    /**
     * 自己的全局启停。收到 {@code false} 之后，平台会拦下该插件自有端点的请求（管理面除外）。
     *
     * <p>插件可按需覆写；业务状态由插件自己的 API 与存储管理。
     */
    default void onGlobalStateChanged(boolean enabled) {
    }
}
