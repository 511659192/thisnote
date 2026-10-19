package com.thisnote.ext.tags;

import com.thisnote.ext.tags.service.AutoTagService;
import com.thisnote.ext.tags.service.TagUsageRefreshService;
import com.thisnote.ext.tags.service.TagsService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.event.NoteTextChangedEvent;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.MainContainerListeners;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class TagsPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "tags";

    private MainContainerListeners listeners;

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
        TagsService tagsService = ctx.getBean(TagsService.class);
        tagsService.invalidateUsage();
        ctx.getBean(AutoTagService.class).setMainEventPublisher(getMainApplicationContext());
        listeners = new MainContainerListeners(getMainApplicationContext());
        listeners.onPayload(NoteChangedEvent.class, change -> {
            if (change.kind() == NoteChangedEvent.Kind.DELETED && tagsService.removeNote(change.noteId())) {
                log.info("笔记已删除，标签一并清除：{}", change.noteId());
            }
        });
        listeners.onPayload(NoteTextChangedEvent.class, change -> {
            ctx.getBean(TagUsageRefreshService.class).request(change.noteId());
        });
        listeners.onStateReset(PLUGIN_ID, "标签已清空", tagsService::clear);
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
