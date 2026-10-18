package com.thisnode.ext.attachmentimporter;

import com.thisnode.ext.attachmentimporter.service.AttachmentImporterService;
import com.thisnode.ext.attachmentimporter.service.PluginStateResetListener;
import com.thisnode.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 附件导入插件：定时扫描附件目录，把未被卡片引用的图片自动生成笔记与卡片。
 *
 * <p>形态与 card-importer 相同：只做「发现 + 提交申请」，笔记与卡片由核心插件（notes）
 * 通过 {@code CardChangedEvent.requestCardCreate} 创建；本插件不写笔记数据。
 *
 * <p>id 与版本来自 {@code plugin.properties}，这里只保留端点/数据目录要用的稳定 id。
 */
public class AttachmentImporterPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "attachment-importer";

    private PluginStateResetListener stateResetListener;

    public AttachmentImporterPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, AttachmentImporterStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        AttachmentImporterService service = ctx.getBean(AttachmentImporterService.class);
        service.setMainEventPublisher(getMainApplicationContext());
        stateResetListener = new PluginStateResetListener();
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }
}
