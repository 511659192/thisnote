package com.thisnote.ext.tags;

import com.thisnote.ext.tags.service.AutoTagService;
import com.thisnote.ext.tags.listener.NoteChangedEventListener;
import com.thisnote.ext.tags.listener.PluginStateResetListener;
import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.ext.tags.service.TagSuggestionService;
import com.thisnote.ext.tags.service.TagUsageRefreshService;
import com.thisnote.ext.tags.service.TagsService;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class TagsPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "tags";

    private NoteChangedEventListener noteChangedEventListener;
    private PluginStateResetListener stateResetListener;

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
        TagsRepository tagsRepository = ctx.getBean(TagsRepository.class);
        TagUsageRefreshService tagUsageRefreshService = ctx.getBean(TagUsageRefreshService.class);
        tagsService.setMainEventPublisher(getMainApplicationContext());
        tagsService.invalidateUsage();
        ctx.getBean(TagSuggestionService.class).setMainEventPublisher(getMainApplicationContext());
        noteChangedEventListener = new NoteChangedEventListener(tagsRepository, tagUsageRefreshService);
        stateResetListener = new PluginStateResetListener(tagsRepository);
        getMainApplicationContext().addApplicationListener(noteChangedEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (noteChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteChangedEventListener);
            noteChangedEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }
}
