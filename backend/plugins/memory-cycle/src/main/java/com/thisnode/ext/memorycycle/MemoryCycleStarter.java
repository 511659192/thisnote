package com.thisnode.ext.memorycycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 插件自己的 Spring Boot 入口（SBP 的 primarySource）。
 *
 * <p>没有数据库、没有 MyBatis、也不扫 {@code @Mapper}：排期写在本插件自己的 {@code scheduling.json}，
 * 是否开启由排期是否存在决定；插件侧既没有表也没有 mapper ——
 * 依赖里连 mybatis 都不再声明，所以不必（也无法）再显式排除它的自动配置。
 */
@SpringBootApplication
public class MemoryCycleStarter {

    public static void main(String[] args) {
        SpringApplication.run(MemoryCycleStarter.class, args);
    }
}
