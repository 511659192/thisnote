package com.thisnode.ext.search;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 插件自己的 Spring Boot 入口（SBP 的 primarySource）。
 *
 * <p>本插件没有控制器与数据访问，容器是空的 —— {@code @SpringBootApplication}
 * 扫描本包扫不到任何 bean 也无妨；SBP 需要它来起插件的子容器。
 */
@SpringBootApplication
public class SearchStarter {
}
