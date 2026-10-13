package com.thisnode.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件 jar 的**契约测试**（不启 Spring 上下文）。
 *
 * <p>取代早期的 {@code PluginWiringTest}：那时插件是被打进应用、由 {@code @AutoConfiguration} +
 * {@code imports} 装配的，所以测试断言的是"装配声明存在"；现在插件是 {@code plugins/} 目录里的
 * 外置 jar，由 SBP/PF4J 装载 —— 于是真正要守住的是**jar 里必须有什么**：
 *
 * <ol>
 *   <li>{@code plugin.properties}（PF4J 的 PropertiesPluginDescriptorFinder 读它）；
 *   <li>{@code plugin.class} 指向的那个类（PF4J 用它做生命周期）；</li>
 *   <li>界面产物**不在 jar 里**（产物分离）：必须已交付到 {@code frontend/runtime/plugins/<id>/}；</li>
 *   <li><b>版本单源</b>：描述符里的版本必须等于这个 jar 的构建版本 —— 手写常量会在这里被抓住。</li>
 * </ol>
 *
 * <p>这几样正是迁移过程中反复出问题的地方（陈旧 target、描述符缺失、资源位置变化、版本漂移），
 * 而且是**构建产物**层面的约束 —— 单元测试里断言它们，比断言装配注解更贴近现在的形态。
 *
 * <p>插件 jar 没构建出来时**跳过**（{@code mvn test -pl backend} 单独跑时是正常的），
 * 不把"还没构建"当成失败。
 */
class PluginJarContractTest {

    /**
     * 插件模块在 {@code backend/plugins/<dir>}，jar 名带 Maven 版本（{@code plugin-<id>-<v>.jar}），
     * 按前缀找，不把版本写死在测试里。
     */
    private static Path jarOf(String moduleDir, String jarPrefix) throws Exception {
        Path target = Path.of("..", "plugins", moduleDir, "target").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(target), "模块尚未构建：" + target);
        try (var entries = Files.list(target)) {
            List<Path> jars = entries
                    .filter(p -> p.getFileName().toString().matches(jarPrefix + "-\\d.*\\.jar"))
                    .toList();
            Assumptions.assumeTrue(!jars.isEmpty(), "插件 jar 尚未构建：" + target);
            return jars.get(0);
        }
    }

    @ParameterizedTest(name = "{2} 的 jar 携带完整的插件契约")
    @CsvSource({
            "notes,plugin-notes,notes,true",
            "favorite,plugin-favorite,favorite,true",
            "memory-cycle,plugin-memory-cycle,memory-cycle,true"
    })
    void pluginJarCarriesItsContract(String moduleDir, String jarPrefix, String pluginId, boolean hasAssets) throws Exception {
        // 前后端拆分对齐后：notes 也有自己的界面侧（宿主 UI 的笔记域迁成了 notes 微应用）——
        // 三个插件都有界面产物，全部走 frontend/runtime/plugins/<id>/ 磁盘交付。
        Path jar = jarOf(moduleDir, jarPrefix);

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // 1) 描述符：plugin.properties 在 jar 根，键齐全
            ZipEntry descriptor = zip.getEntry("plugin.properties");
            assertThat(descriptor).as("plugin.properties 必须在 jar 根（PF4J 的查找位置）").isNotNull();
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(descriptor)) {
                props.load(in);
            }
            assertThat(props.getProperty("plugin.id")).isEqualTo(pluginId);
            assertThat(props.getProperty("plugin.version")).isNotBlank();
            assertThat(props.getProperty("plugin.provider")).isNotBlank();

            // 1b) 版本单源：描述符版本 == 这个 jar 自己的构建版本（父 pom 过滤注入的结果）
            ZipEntry buildInfo = zip.getEntry("META-INF/maven/com.thisnode/" + jarPrefix + "/pom.properties");
            assertThat(buildInfo).as("jar 里应带 Maven 构建信息（pom.properties）").isNotNull();
            Properties build = new Properties();
            try (InputStream in = zip.getInputStream(buildInfo)) {
                build.load(in);
            }
            assertThat(props.getProperty("plugin.version"))
                    .as("plugin.properties 的版本必须由 Maven 注入（@project.version@），不能手写")
                    .isEqualTo(build.getProperty("version"));

            // 2) 插件类：descriptor 里声明的那个类必须在 jar 里
            String pluginClass = props.getProperty("plugin.class");
            assertThat(pluginClass).as("plugin.class 必须声明").isNotBlank();
            assertThat(zip.getEntry(pluginClass.replace('.', '/') + ".class"))
                    .as("%s 必须在 jar 里", pluginClass)
                    .isNotNull();
            // 3) 界面资源不得打进 jar：一律由界面侧自行交付与提供（后端不端出静态资源）
            if (hasAssets) {
                assertThat(zip.stream().map(ZipEntry::getName)
                                .filter((n) -> n.startsWith("plugin/" + pluginId + "/")).count())
                        .as("界面资源不得打进插件 jar（由界面侧自行交付）")
                        .isZero();
            }
        }
    }
}
