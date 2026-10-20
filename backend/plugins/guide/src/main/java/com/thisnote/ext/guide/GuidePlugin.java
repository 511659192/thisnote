package com.thisnote.ext.guide;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class GuidePlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "guide";

    public GuidePlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, GuideStarter.class);
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        log.info("使用指南插件已启动");
    }

    @Override
    public void stop() {
        log.info("使用指南插件正在停止");
        super.stop();
    }
}
