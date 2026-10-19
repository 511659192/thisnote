package com.thisnote.ext.attachmentimporter;

import com.thisnote.ext.attachmentimporter.service.AttachmentImporterService;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

/**
 * 附件导入插件：定时扫描附件目录，把未被卡片引用的图片自动生成笔记与卡片。
 *
 * <p>形态与 card-importer 相同：只做「发现 + 提交申请」，笔记与卡片由核心插件（notes）
 * 通过 {@code CardChangedEvent.requestCardCreate} 创建；本插件不写笔记数据。
 *
 * <p>id 与版本来自 {@code plugin.properties}，这里只保留端点/数据目录要用的稳定 id。
 */
public class AttachmentImporterPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "attachment-importer";

    public AttachmentImporterPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, AttachmentImporterStarter.class);
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        AttachmentImporterService service = ctx.getBean(AttachmentImporterService.class);
        service.setMainEventPublisher(getMainApplicationContext());
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof PayloadApplicationEvent<?> payloadEvent
                && payloadEvent.getPayload() instanceof PluginStateResetEvent resetEvent
                && resetEvent.expects(PLUGIN_ID)) {
            resetEvent.complete(PLUGIN_ID, PluginResetResult.skipped(PLUGIN_ID, "attachment-importer 无自有状态"));
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }
}
