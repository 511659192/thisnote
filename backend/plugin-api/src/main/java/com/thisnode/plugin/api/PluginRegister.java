package com.thisnode.plugin.api;

import org.pf4j.ExtensionPoint;

/**
 * 插件向平台贡献的全部能力（**唯一的扩展点**）。
 *
 * <p>本平台的插件契约几乎全是 PF4J 原生机制：插件是 {@code plugins/*.jar}，身份/版本/provider
 * 来自 {@code plugin.properties}（PF4J 的 {@code PropertiesPluginDescriptorFinder}），契约版本用
 * PF4J 原生的 {@code plugin.requires} + {@code VersionManager} 校验，能力由插件里的
 * {@code @Extension} 类贡献 —— 插件作者只需要实现本接口并按需覆写（除显示名与说明外都有默认实现）。
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
     * <p>改契约（新增/改语义的方法、动 {@link PluginDataRepository} 的文件格式）时递增它。
     */
    String CONTRACT_VERSION = "4.0.0";

    /** 管理页上的显示名。 */
    String title();

    /**
     * 一句话说明。
     *
     * <p>本来该用 PF4J 描述符的 {@code plugin.description} —— 但 PF4J 的
     * {@code PropertiesPluginDescriptorFinder} 用 {@code Properties.load(InputStream)} 读文件
     * （ISO-8859-1），中文必然乱码，而它不支持指定字符集。所以这段**人类可读文案**放在这里
     * （Java 源码是 UTF-8）；平台取不到这个扩展时回落到 {@code plugin.description}。
     */
    String description();

    /**
     * 不允许停用/在线重载：只有承载主体业务领域的插件（参考 notes）覆写为 {@code true}。
     * 管理面会直接拒绝（409），端点闸门也总是放行它。
     */
    default boolean undeactivatable() {
        return false;
    }

    /** 自报健康；不覆写即「运行正常」。 */
    default PluginHealth health() {
        return PluginHealth.ok("运行正常");
    }

    /**
     * 自己的全局启停。收到 {@code false} 之后，平台会拦下该插件自有端点的请求（管理面除外）。
     *
     * <p>卡片类插件可以忽略它——卡片级开关走 {@link #onEntityStateChanged}。
     */
    default void onGlobalStateChanged(boolean enabled) {
    }

    /**
     * 自己的某个 per-entity 状态翻转了。
     *
     * <p>这是插件初始化/释放自己数据的时机：记忆曲线插件在卡片被开启时建一条「立即到期」的排期，
     * 卡片关闭时保留它（历史不丢）。
     *
     * <p>没有 entityType 参数：平台不解释实体是什么（见 {@link PluginDataRepository}），
     * 每个插件只有一类实体，id 自己就说明了一切。
     *
     * @param entityId 实体 id
     * @param enabled  新状态
     */
    default void onEntityStateChanged(String entityId, boolean enabled) {
    }
}
