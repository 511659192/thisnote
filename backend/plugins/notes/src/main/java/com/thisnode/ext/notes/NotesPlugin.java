package com.thisnode.ext.notes;

import com.thisnode.ext.notes.repository.BookRepository;
import com.thisnode.ext.notes.service.AttachmentService;
import com.thisnode.ext.notes.service.listener.AttachmentQueryEventListener;
import com.thisnode.ext.notes.service.listener.CardAttachmentCleanupListener;
import com.thisnode.ext.notes.service.listener.CardCreateRequestListener;
import com.thisnode.ext.notes.service.listener.CardQueryEventListener;
import com.thisnode.ext.notes.service.CardService;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.service.BookService;
import com.thisnode.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 笔记核心插件：主体原来的业务领域（笔记 / 卡片 / 搜索 / 标签 / 快照 / IO / 笔记目录同步）。
 *
 * <p>按 sbp 最佳实践（参考 sbp-demo 的 plugin-app），主体应用是<b>纯平台</b>——只做插件生命周期
 * 管理、共享 DataSource 与界面产物托管，不带业务。业务领域以插件形态交付：本插件在自己的
 * Spring 子容器里运行，控制器由 SBP 挂进主 MVC（外部路径不变，仍是 {@code /api/**}）。
 *
 * <p>favorite / memory-cycle 仍各自管理状态与界面；card-importer 通过 plugin-api 事件查询卡片。
 * 笔记与卡片 ID 独立于物理文件名，标题改名只更新文件索引。
 *
 * <p><b>本插件不允许停用/在线重载</b>（覆写 {@code undeactivatable()}）：管理面直接拒绝
 * （{@code 409}），否则整个应用的笔记能力会随一次误点整体下线。
 */
public class NotesPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "notes";

    private CardAttachmentCleanupListener attachmentCleanupListener;
    private CardCreateRequestListener cardCreateRequestListener;
    private CardQueryEventListener cardQueryEventListener;
    private AttachmentQueryEventListener attachmentQueryEventListener;

    public NotesPlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的 configurer。
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, NotesStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap,
                                  org.springframework.context.support.GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     org.springframework.context.support.GenericApplicationContext ctx) {
        BookRepository bookRepository = ctx.getBean(BookRepository.class);
        bookRepository.initializeDefaultGroup();
        ctx.getBean(CardService.class).setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(NoteService.class).setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(BookService.class).setMainEventPublisher(getMainApplicationContext());
        attachmentCleanupListener = new CardAttachmentCleanupListener(ctx.getBean(AttachmentService.class));
        cardCreateRequestListener = new CardCreateRequestListener(ctx.getBean(CardService.class), ctx.getBean(NoteService.class));
        cardQueryEventListener = new CardQueryEventListener(ctx.getBean(CardService.class));
        attachmentQueryEventListener = new AttachmentQueryEventListener(ctx.getBean(AttachmentService.class));
        getMainApplicationContext().addApplicationListener(attachmentCleanupListener);
        getMainApplicationContext().addApplicationListener(cardCreateRequestListener);
        getMainApplicationContext().addApplicationListener(cardQueryEventListener);
        getMainApplicationContext().addApplicationListener(attachmentQueryEventListener);
    }

    @Override
    public void stop() {
        if (attachmentCleanupListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentCleanupListener);
            attachmentCleanupListener = null;
        }
        if (cardCreateRequestListener != null) {
            getMainApplicationContext().removeApplicationListener(cardCreateRequestListener);
            cardCreateRequestListener = null;
        }
        if (cardQueryEventListener != null) {
            getMainApplicationContext().removeApplicationListener(cardQueryEventListener);
            cardQueryEventListener = null;
        }
        if (attachmentQueryEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentQueryEventListener);
            attachmentQueryEventListener = null;
        }
        super.stop();
    }

}
