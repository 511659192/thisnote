package com.thisnode.ext.notes;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginKind;
import com.thisnode.plugin.support.ThisnodePlugin;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 笔记核心插件：主体原来的业务领域（笔记 / 卡片 / 搜索 / 标签 / 附件 / 快照 / IO / 笔记目录同步）。
 *
 * <p>按 sbp 最佳实践（参考 sbp-demo 的 plugin-app），主体应用是<b>纯平台</b>——只做插件生命周期
 * 管理、共享 DataSource 与前端产物托管，不带业务。业务领域以插件形态交付：本插件在自己的
 * Spring 子容器里运行，控制器由 SBP 挂进主 MVC（外部路径不变，仍是 {@code /api/**}）。
 *
 * <p>与兄弟插件的关系：<b>没有依赖</b>。favorite / memory-curve 各自把状态写进自己的文件
 * （{@code plugin-data.json} 与 {@code plugins/memory-curve/scheduling.meta}），不读笔记数据，
 * 因此本插件缺位时它们照样能独立启动；复习页与收藏列表要展示的卡片内容，由前端用本插件的
 * 公开 API（{@code /api/notes}、{@code /api/notes/{id}/page}）现拼。
 *
 * <p><b>核心插件不允许停用</b>（{@link PluginKind#CORE}）：底座的管理面会直接拒绝
 * （{@code 409}），否则整个应用的笔记能力会随一次误点整体下线。
 */
public class NotesPlugin extends ThisnodePlugin {

    public NotesPlugin(PluginWrapper wrapper) {
        super(wrapper, PluginKind.CORE, "笔记核心",
                "笔记 / 卡片 / 搜索 / 标签 / 附件 / 快照 / 导入导出 —— 主体原有的业务领域，以插件形态交付");
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, NotesStarter.class);
    }

    /**
     * 【spike，验证完即删】把**主容器**的 publisher 注册进插件容器。
     *
     * <p>importBean(ApplicationEventPublisher.class) 试过、不行：主容器里没有该类型的 bean，
     * 注入会回退成插件自己的容器（日志里 publisher == 插件容器）。所以这里显式注册一个。
     * 注意此时 getApplicationContext() 仍为 null —— 必须用回调给的 ctx。
     */
    /**
     * 把主容器的 publisher 交给插件容器里的 bean：卡片变更事件要从**主容器**发出去才跨得过插件
     * 边界（实测：插件在自己容器里发的事件，兄弟插件的 listener 收不到）。
     *
     * <p>注册发生在容器 refresh 之后，所以消费方要用 {@code ObjectProvider} 延迟取，
     * 不能构造器直接注入。
     */
    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     org.springframework.context.support.GenericApplicationContext ctx) {
        ctx.getBeanFactory().registerSingleton("mainEventPublisher",
                new com.thisnode.ext.notes.config.MainEventPublisher(getMainApplicationContext()));
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("核心业务随平台运行");
    }
}
