package com.thisnode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * thisnode 平台主体 —— 按 sbp 最佳实践（参考 sbp-demo）它是**纯平台**：插件生命周期管理
 * （发现 / 契约校验 / 激活 / 健康检查）、插件管理面（/api/plugins）与 /plugin-assets 托管、
 * SPA 静态资源。业务领域全部以插件交付（核心业务在 plugin-notes，功能插件 favorite /
 * memory-curve 各自独立）。
 *
 * <p><b>不连数据库</b>：笔记数据以本地 md 文件为权威，插件状态也在文件里 —— 平台没有任何表，
 * 唯一残留的"数据库痕迹"是历史上写下的迁移账本，已不再访问。
 *
 * <p>交付形态是单体：一个进程、一个端口 —— 后端自己端出前端产物（Maven 把前端产物拷进
 * {@code classpath:/static/}，走 Spring Boot 默认静态资源映射）。
 *
 * <p>组件扫描范围显式列举平台包，其中刻意不含 {@code com.thisnode.ext}：插件由 SBP/PF4J
 * 从 plugins/ 目录装载，不靠装配声明。平台包：config（Web 装配）、plugin（插件底座：
 * 注册表/状态/闸门 + 资源托管）、service（平台级守护任务）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.thisnode.api",
        "com.thisnode.config",
        "com.thisnode.plugin",
        "com.thisnode.service",
})
@EnableScheduling
public class ThisnodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThisnodeApplication.class, args);
    }
}
