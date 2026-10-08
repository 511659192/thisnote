package com.thisnode.ext.favorite;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 插件自己的 Spring Boot 入口（SBP 的 primarySource）。
 *
 * <p>{@code @SpringBootApplication} 会扫描本包，于是 {@link FavoriteController} 成为**插件容器**里的 bean；
 * SBP 的 {@code SbpWebConfigurer} 再把插件里的 {@code @RestController} 挂进主容器的 MVC，
 * 所以外部看到的路径仍是 {@code /api/plugins/favorite/**}。
 *
 * <p>注意这里**没有** main 方法：插件不自己启动，它由 SBP 在插件生命周期里 run 起来。
 */
@SpringBootApplication
public class FavoriteStarter {
}