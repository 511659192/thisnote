package com.thisnode.ext.notes;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 插件自己的 Spring Boot 入口（SBP 的 primarySource）。
 *
 * <p>{@code @SpringBootApplication} 扫描本包，业务控制器成为**插件容器**里的 bean；
 * SBP 的 {@code SbpWebConfigurer} 把它们挂进主容器的 MVC —— 外部路径不变
 * （{@code /api/notes/**} 不存在，控制器本来就声明 {@code /api/**} 直连路径）。
 *
 * <p>没有数据库、没有 MyBatis、也不扫 {@code @Mapper}：笔记数据以本地 md 文件为权威
 * （见 store 包），插件侧既没有表也没有 mapper。
 */
@SpringBootApplication
public class NotesStarter {
}
