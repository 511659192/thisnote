package com.thisnode.plugin.api;

/**
 * 插件类型。
 *
 * <p>底座最初只有卡片插件（给卡片右上角加一个开关 + 挂个组件），但「盖在应用上的能力」是另一种
 * 东西：它有账号授权、有整库范围的后台动作、有自己的一张表、还要在设置页占一块 UI。两者需要的
 * 契约不同，所以显式区分，而不是硬塞进同一个接口。
 */
public enum PluginKind {

    /**
     * 核心插件：承载主体原来的业务领域（参考 notes —— 笔记/卡片/搜索/附件）。
     *
     * <p>按 sbp 最佳实践，主体应用是**纯平台**（插件生命周期管理 + 共享 DataSource），
     * 业务领域以插件形态交付。兄弟插件**不依赖它**：各自的排期/收藏状态写在自己的文件里，
     * 要展示的卡片内容由前端用核心的公开 API 现拼。因此它**不允许被停用**（管理面直接拒绝；
     * 底座也会放行其端点）—— 理由是整块笔记能力会随一次误点整体下线，而不是兄弟插件会失去契约。
     */
    CORE("core", "核心插件"),

    /** 卡片插件：per-entity 开关 + 卡片工具槽（参考 memorycurve）。 */
    CARD("card", "卡片插件"),

    /**
     * 实体插件：给某类实体（笔记 / 卡片 / …）加一个 per-entity 状态与工具，
     * 并可以带自己的页面（参考 favorite：笔记级的 ★ 收藏 + 收藏页）。
     *
     * <p>与 CARD 的区别只在槽位：CARD 固定渲染在卡片工具行里，ENTITY 自己声明作用在哪种实体上
     * （前端据此决定工具挂在哪、列表页列什么）。
     */
    ENTITY("entity", "实体插件"),

    /** 服务插件：全局启停 + 配置 + 自带迁移 + 设置页区块（参考 baidu-pan）。 */
    SERVICE("service", "服务插件");

    private final String id;
    private final String label;

    PluginKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
