package com.thisnote;

import com.thisnote.plugin.api.util.DataRootSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * thisnote 平台主体 —— 按 sbp 最佳实践（参考 sbp-demo）它是**纯平台**：插件生命周期管理
 * （发现 / 激活）与插件管理面（/api/plugins/get 等动作接口）。
 * 业务领域全部以插件交付（核心业务在 plugin-notes，功能插件 favorite / memory-cycle 各自独立）。
 *
 * <p><b>不连数据库</b>：笔记数据以本地 md 文件为权威，插件状态也在文件里 —— 平台没有任何表。
 *
 * <p><b>纯 API</b>：产物分离 —— 不端出任何静态资源（{@code add-mappings: false}，
 * 同时是 SBP 资源链 500 毒链的护栏）；宿主界面由界面侧独立提供与部署。
 *
 * <p>组件扫描范围显式列举平台包，其中刻意不含 {@code com.thisnote.ext}：插件由 SBP/PF4J
 * 从 runtime/plugins/ 目录装载，不靠装配声明。平台包：api（异常映射）、config（Web 装配）、
 * plugin（插件底座：注册表/状态/闸门）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.thisnote.api",
        "com.thisnote.config",
        "com.thisnote.plugin",
})
@EnableScheduling
public class ThisnoteApplication {

    public static void main(String[] args) {
        DataRootSettings.applyConfiguredDataRoot(args);
        SpringApplication.run(ThisnoteApplication.class, args);
    }
}
