package com.thisnote.ext.notes;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.request.CreateCardRequest;
import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.ext.notes.service.BookService;
import com.thisnote.plugin.api.event.AttachmentAdoptEvent;
import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import com.thisnote.plugin.api.event.AttachmentQueryEvent;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.CardQueryEvent;
import com.thisnote.plugin.api.event.FileChangeEvent;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

import java.nio.file.Path;
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
public class NotesPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "notes";

    private AttachmentService attachmentService;
    private NoteService noteService;
    private CardService cardService;
    private Path attachmentsDirectory;

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
        cardService = ctx.getBean(CardService.class);
        noteService = ctx.getBean(NoteService.class);
        attachmentService = ctx.getBean(AttachmentService.class);
        cardService.setMainEventPublisher(getMainApplicationContext());
        noteService.setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(BookService.class).setMainEventPublisher(getMainApplicationContext());

        attachmentsDirectory = ctx.getBean(NotesConfig.class).getRootDir().resolve("attachments");
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof PayloadApplicationEvent<?> payloadEvent)) {
            return;
        }
        Object payload = payloadEvent.getPayload();
        if (payload instanceof FileChangeEvent change) {
            republishAttachmentChange(change, attachmentsDirectory);
        } else if (payload instanceof CardChangedEvent change) {
            if (change.kind() == CardChangedEvent.Kind.DELETED) {
                attachmentService.deleteIfUnreferenced(change.removedAttachmentFileNames());
            } else if (change.kind() == CardChangedEvent.Kind.REQUEST) {
                var request = change.createRequest();
                var note = noteService.create(request.noteTitle(), request.bookId(), List.of());
                // 申请方可以在 meta 里给出初始宽高（附件导入按图片尺寸算）；缺失时用默认 720×260。
                var meta = request.card().meta();
                Double width = meta == null || meta.w() <= 0 ? null : meta.w();
                Double height = meta == null || meta.h() <= 0 ? null : meta.h();
                cardService.create(note.noteId(), new CreateCardRequest(null, 0d, 0d, width, height, request.card().content(), null), request.card().sourceFileName());
            }
        } else if (payload instanceof CardQueryEvent query) {
            try {
                query.result().complete(cardService.listUnowned());
            } catch (Exception e) {
                query.result().completeExceptionally(e);
            }
        } else if (payload instanceof AttachmentQueryEvent query) {
            try {
                query.result().complete(attachmentService.unmanagedAttachments());
            } catch (Exception e) {
                // 读取卡片/目录时可能撞上正在写入/被删除的文件，按查询失败处理，不打断事件总线
                query.result().completeExceptionally(e);
            }
        } else if (payload instanceof AttachmentAdoptEvent adopt) {
            try {
                adopt.result().complete(attachmentService.adopt(adopt.fileName()));
            } catch (Exception e) {
                adopt.result().completeExceptionally(e);
            }
        } else if (payload instanceof PluginStateResetEvent resetEvent) {
            try {
                noteService.resetIndexes();
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.reset(PLUGIN_ID, "笔记/卡片/附件索引已重置（文件保留）"));
                }
            } catch (RuntimeException e) {
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.failed(PLUGIN_ID, e));
                }
                log.warn("插件 {} 状态重置失败", PLUGIN_ID, e);
            }
        }
    }

    private void republishAttachmentChange(FileChangeEvent change,
                                           Path attachmentsDirectory) {
        Path current = normalizedWithin(change.url(), attachmentsDirectory);
        Path previous = change.previousUrl() == null
                ? null
                : normalizedWithin(change.previousUrl(), attachmentsDirectory);
        AttachmentChangedEvent attachmentEvent;
        if (change.operation() == FileChangeEvent.Operation.RENAME && current != null && previous != null) {
            attachmentEvent = AttachmentChangedEvent.renamed(
                    previous.getFileName().toString(),
                    current.getFileName().toString());
        } else if (current != null) {
            attachmentEvent = switch (change.operation()) {
                case CREATED -> AttachmentChangedEvent.created(current.getFileName().toString());
                case UPDATED -> AttachmentChangedEvent.updated(current.getFileName().toString());
                case DELETED, RENAME -> AttachmentChangedEvent.deleted(current.getFileName().toString());
            };
        } else if (previous != null) {
            attachmentEvent = AttachmentChangedEvent.deleted(previous.getFileName().toString());
        } else {
            return;
        }
        getMainApplicationContext().publishEvent(attachmentEvent);
    }

    private static Path normalizedWithin(Path path,
                                         Path directory) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedDirectory) && !normalized.equals(normalizedDirectory)
                ? normalized
                : null;
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }

}
