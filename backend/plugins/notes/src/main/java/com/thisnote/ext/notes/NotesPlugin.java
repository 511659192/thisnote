package com.thisnote.ext.notes;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.repository.BookRepository;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.ext.notes.service.BookService;
import com.thisnote.ext.notes.service.StorageInitializationService;
import com.thisnote.ext.notes.listener.AttachmentAdoptEventListener;
import com.thisnote.ext.notes.listener.AttachmentBytesQueryEventListener;
import com.thisnote.ext.notes.listener.AttachmentQueryEventListener;
import com.thisnote.ext.notes.listener.AttachmentStageFutureEventListener;
import com.thisnote.ext.notes.listener.CardAttachmentCleanupListener;
import com.thisnote.ext.notes.listener.CardCreateRequestListener;
import com.thisnote.ext.notes.listener.CardQueryEventListener;
import com.thisnote.ext.notes.listener.CardStageFutureEventListener;
import com.thisnote.ext.notes.listener.FileChangeEventListener;
import com.thisnote.ext.notes.listener.NoteContentFutureEventListener;
import com.thisnote.ext.notes.listener.NoteListFutureEventListener;
import com.thisnote.ext.notes.listener.NoteTitleUpdateFutureEventListener;
import com.thisnote.ext.notes.listener.PluginStateResetListener;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class NotesPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "notes";

    private FileChangeEventListener fileChangeListener;
    private CardAttachmentCleanupListener cardAttachmentCleanupListener;
    private CardCreateRequestListener cardCreateRequestListener;
    private CardQueryEventListener cardQueryEventListener;
    private CardStageFutureEventListener cardStageFutureEventListener;
    private AttachmentQueryEventListener attachmentQueryEventListener;
    private AttachmentAdoptEventListener attachmentAdoptEventListener;
    private AttachmentStageFutureEventListener attachmentStageFutureEventListener;
    private NoteContentFutureEventListener noteContentFutureEventListener;
    private NoteListFutureEventListener noteListFutureEventListener;
    private NoteTitleUpdateFutureEventListener noteTitleUpdateFutureEventListener;
    private AttachmentBytesQueryEventListener attachmentBytesQueryEventListener;
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
        ctx.getBean(StorageInitializationService.class).initialize();
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
        CardRepository cardRepository = ctx.getBean(CardRepository.class);
        NoteRepository noteRepository = ctx.getBean(NoteRepository.class);
        BookRepository bookRepository = ctx.getBean(BookRepository.class);
        NotesMapper notesMapper = ctx.getBean(NotesMapper.class);
        cardQueryEventListener = new CardQueryEventListener(cardRepository, notesMapper);
        cardStageFutureEventListener = new CardStageFutureEventListener(
                ctx.getBean(NotesConfig.class), ctx.getBean(FileHelper.class));
        attachmentQueryEventListener = new AttachmentQueryEventListener(attachmentService);
        attachmentAdoptEventListener = new AttachmentAdoptEventListener(attachmentService);
        attachmentStageFutureEventListener = new AttachmentStageFutureEventListener(attachmentService);
        noteContentFutureEventListener = new NoteContentFutureEventListener(noteService, cardService);
        noteListFutureEventListener = new NoteListFutureEventListener(noteRepository, notesMapper);
        noteTitleUpdateFutureEventListener = new NoteTitleUpdateFutureEventListener(noteService);
        attachmentBytesQueryEventListener = new AttachmentBytesQueryEventListener(attachmentService);
        stateResetListener = new PluginStateResetListener(noteRepository, cardRepository, attachmentService, bookRepository);
        getMainApplicationContext().addApplicationListener(fileChangeListener);
        getMainApplicationContext().addApplicationListener(cardAttachmentCleanupListener);
        getMainApplicationContext().addApplicationListener(cardCreateRequestListener);
        getMainApplicationContext().addApplicationListener(cardQueryEventListener);
        getMainApplicationContext().addApplicationListener(cardStageFutureEventListener);
        getMainApplicationContext().addApplicationListener(attachmentQueryEventListener);
        getMainApplicationContext().addApplicationListener(attachmentAdoptEventListener);
        getMainApplicationContext().addApplicationListener(attachmentStageFutureEventListener);
        getMainApplicationContext().addApplicationListener(noteContentFutureEventListener);
        getMainApplicationContext().addApplicationListener(noteListFutureEventListener);
        getMainApplicationContext().addApplicationListener(noteTitleUpdateFutureEventListener);
        getMainApplicationContext().addApplicationListener(attachmentBytesQueryEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
        log.info("笔记插件已启动：root={}", ctx.getBean(NotesConfig.class).getRootDir());
    }

    @Override
    public void stop() {
        log.info("笔记插件正在停止");
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
        if (cardStageFutureEventListener != null) {
            getMainApplicationContext().removeApplicationListener(cardStageFutureEventListener);
            cardStageFutureEventListener = null;
        }
        if (attachmentQueryEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentQueryEventListener);
            attachmentQueryEventListener = null;
        }
        if (attachmentAdoptEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentAdoptEventListener);
            attachmentAdoptEventListener = null;
        }
        if (attachmentStageFutureEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentStageFutureEventListener);
            attachmentStageFutureEventListener = null;
        }
        if (noteContentFutureEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteContentFutureEventListener);
            noteContentFutureEventListener = null;
        }
        if (noteListFutureEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteListFutureEventListener);
            noteListFutureEventListener = null;
        }
        if (noteTitleUpdateFutureEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteTitleUpdateFutureEventListener);
            noteTitleUpdateFutureEventListener = null;
        }
        if (attachmentBytesQueryEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentBytesQueryEventListener);
            attachmentBytesQueryEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }

}
