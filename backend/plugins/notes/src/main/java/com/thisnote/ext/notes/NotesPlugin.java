package com.thisnote.ext.notes;

import com.thisnote.ext.notes.model.request.CreateCardRequest;
import com.thisnote.ext.notes.repository.BookRepository;
import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.ext.notes.service.BookService;
import com.thisnote.plugin.api.event.AttachmentAdoptEvent;
import com.thisnote.plugin.api.event.AttachmentQueryEvent;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.CardQueryEvent;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.MainContainerListeners;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

import java.util.List;

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

    /** 本插件注册到主容器的全部跨插件监听器（停止时统一摘除）。 */
    private MainContainerListeners listeners;

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
        CardService cardService = ctx.getBean(CardService.class);
        NoteService noteService = ctx.getBean(NoteService.class);
        AttachmentService attachmentService = ctx.getBean(AttachmentService.class);
        cardService.setMainEventPublisher(getMainApplicationContext());
        noteService.setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(BookService.class).setMainEventPublisher(getMainApplicationContext());

        listeners = new MainContainerListeners(getMainApplicationContext());
        // 卡片被删后清理不再被引用的附件
        listeners.onPayload(CardChangedEvent.class, change -> {
            if (change.kind() == CardChangedEvent.Kind.DELETED) {
                attachmentService.deleteIfUnreferenced(change.removedAttachmentFileNames());
            }
        });
        // 导入插件的建卡申请：新建一篇笔记，再以固定文件名落一张卡
        listeners.onPayload(CardChangedEvent.class, change -> {
            if (change.kind() != CardChangedEvent.Kind.REQUEST) {
                return;
            }
            var request = change.createRequest();
            var note = noteService.create(request.noteTitle(), request.bookId(), List.of());
            // 申请方可以在 meta 里给出初始宽高（附件导入按图片尺寸算）；缺失时用默认 720×260。
            var meta = request.card().meta();
            Double width = meta == null || meta.w() <= 0 ? null : meta.w();
            Double height = meta == null || meta.h() <= 0 ? null : meta.h();
            cardService.create(note.noteId(), new CreateCardRequest(null, 0d, 0d, width, height, request.card().content(), null), request.card().sourceFileName());
        });
        // 回答 card-importer 的无主卡片查询
        listeners.onPayload(CardQueryEvent.class, query -> {
            try {
                query.result().complete(cardService.listUnowned());
            } catch (Exception e) {
                query.result().completeExceptionally(e);
            }
        });
        // 回答 attachment-importer 的未被管理附件查询
        listeners.onPayload(AttachmentQueryEvent.class, query -> {
            try {
                query.result().complete(attachmentService.unmanagedAttachments());
            } catch (Exception e) {
                // 读取卡片/目录时可能撞上正在写入/被删除的文件，按查询失败处理，不打断事件总线
                query.result().completeExceptionally(e);
            }
        });
        // 接管未被管理的附件：改名 + 记录原始文件名
        listeners.onPayload(AttachmentAdoptEvent.class, adopt -> {
            try {
                adopt.result().complete(attachmentService.adopt(adopt.fileName()));
            } catch (Exception e) {
                adopt.result().completeExceptionally(e);
            }
        });
        listeners.onStateReset("笔记/卡片/附件索引已重置（文件保留）", noteService::resetIndexes);
    }

    @Override
    public void stop() {
        if (listeners != null) {
            listeners.removeAll();
            listeners = null;
        }
        super.stop();
    }

}
