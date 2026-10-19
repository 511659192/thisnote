package com.thisnote.ext.tags;

import com.thisnote.ext.tags.service.AutoTagService;
import com.thisnote.ext.tags.service.TagUsageRefreshService;
import com.thisnote.ext.tags.service.TagsService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.event.NoteTextChangedEvent;
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
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

@Slf4j
public class TagsPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "tags";

    private TagsService tagsService;
    private TagUsageRefreshService tagUsageRefreshService;

    public TagsPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, TagsStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        tagsService = ctx.getBean(TagsService.class);
        tagUsageRefreshService = ctx.getBean(TagUsageRefreshService.class);
        tagsService.invalidateUsage();
        ctx.getBean(AutoTagService.class).setMainEventPublisher(getMainApplicationContext());
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
        if (payload instanceof NoteChangedEvent change) {
            if (change.kind() == NoteChangedEvent.Kind.DELETED && tagsService.removeNote(change.noteId())) {
                log.info("笔记已删除，标签一并清除：{}", change.noteId());
            }
        } else if (payload instanceof NoteTextChangedEvent change) {
            tagUsageRefreshService.request(change.noteId());
        } else if (payload instanceof PluginStateResetEvent resetEvent) {
            try {
                tagsService.clear();
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.reset(PLUGIN_ID, "标签已清空"));
                }
            } catch (RuntimeException e) {
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.failed(PLUGIN_ID, e));
                }
                log.warn("插件 {} 状态重置失败", PLUGIN_ID, e);
            }
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }
}
