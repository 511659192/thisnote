package com.thisnote.ext.notes;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.ext.notes.service.BookService;
import com.thisnote.ext.notes.service.listener.AttachmentAdoptEventListener;
import com.thisnote.ext.notes.service.listener.AttachmentQueryEventListener;
import com.thisnote.ext.notes.service.listener.CardAttachmentCleanupListener;
import com.thisnote.ext.notes.service.listener.CardCreateRequestListener;
import com.thisnote.ext.notes.service.listener.CardQueryEventListener;
import com.thisnote.ext.notes.service.listener.FileChangeEventListener;
import com.thisnote.ext.notes.service.listener.PluginStateResetListener;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

import java.nio.file.Path;

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

    private FileChangeEventListener fileChangeListener;
    private CardAttachmentCleanupListener cardAttachmentCleanupListener;
    private CardCreateRequestListener cardCreateRequestListener;
    private CardQueryEventListener cardQueryEventListener;
    private AttachmentQueryEventListener attachmentQueryEventListener;
    private AttachmentAdoptEventListener attachmentAdoptEventListener;
    private PluginStateResetListener stateResetListener;

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
        ctx.getBean(BookService.class).initializeDefaultGroup();
        CardService cardService = ctx.getBean(CardService.class);
        NoteService noteService = ctx.getBean(NoteService.class);
        AttachmentService attachmentService = ctx.getBean(AttachmentService.class);
        cardService.setMainEventPublisher(getMainApplicationContext());
        noteService.setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(BookService.class).setMainEventPublisher(getMainApplicationContext());

        Path attachmentsDirectory = ctx.getBean(NotesConfig.class).getRootDir().resolve("attachments");
        fileChangeListener = new FileChangeEventListener(attachmentsDirectory, getMainApplicationContext());
        cardAttachmentCleanupListener = new CardAttachmentCleanupListener(attachmentService);
        cardCreateRequestListener = new CardCreateRequestListener(cardService, noteService);
        cardQueryEventListener = new CardQueryEventListener(cardService);
        attachmentQueryEventListener = new AttachmentQueryEventListener(attachmentService);
        attachmentAdoptEventListener = new AttachmentAdoptEventListener(attachmentService);
        stateResetListener = new PluginStateResetListener(noteService);
        getMainApplicationContext().addApplicationListener(fileChangeListener);
        getMainApplicationContext().addApplicationListener(cardAttachmentCleanupListener);
        getMainApplicationContext().addApplicationListener(cardCreateRequestListener);
        getMainApplicationContext().addApplicationListener(cardQueryEventListener);
        getMainApplicationContext().addApplicationListener(attachmentQueryEventListener);
        getMainApplicationContext().addApplicationListener(attachmentAdoptEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (fileChangeListener != null) {
            getMainApplicationContext().removeApplicationListener(fileChangeListener);
            fileChangeListener = null;
        }
        if (cardAttachmentCleanupListener != null) {
            getMainApplicationContext().removeApplicationListener(cardAttachmentCleanupListener);
            cardAttachmentCleanupListener = null;
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
        if (attachmentAdoptEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentAdoptEventListener);
            attachmentAdoptEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }

}
