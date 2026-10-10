package com.thisnode.ext.notes.config;

import com.thisnode.ext.notes.domain.NoteFilePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 笔记核心插件的应用路径与目录引导。
 *
 * <p>这些配置从主体的 {@code application.yml} 随业务一起迁来：业务在哪里，路径配置就在哪里
 * （sbp 约定各应用持有自己的配置）。主体保留的只有平台真正需要的东西
 * （服务器端口、数据源、插件运行时），不再认识 notes / attachments。
 *
 * <p>不再装配 MyBatis：笔记数据以文件为权威，笔记侧没有需要通过 SQL 访问的表。
 */
@Configuration
public class NotesConfig {

    @Value("${thisnode.data-root}")
    private String dataRoot;

    @Value("${thisnode.markdown-root}")
    private String markdownRoot;

    @Value("${thisnode.attachment-root}")
    private String attachmentRoot;


    public Path markdownRoot() {
        return Paths.get(markdownRoot).toAbsolutePath().normalize();
    }

    public Path attachmentRoot() {
        return Paths.get(attachmentRoot).toAbsolutePath().normalize();
    }

    public Path dataRoot() {
        return Paths.get(dataRoot).toAbsolutePath().normalize();
    }

    /**
     * 笔记目录的路径规则：卡片 md（{@code cardRefs/}）、笔记 meta（{@code meta/}）、
     * 台账（{@code ledgers/}）与日志（{@code logs/}）都挂在 {@code thisnode.markdown-root} 下。
     *
     * <p>领域层（{@code NoteRepository}/{@code CardRepository}）依赖这个值对象而不是本配置类 ——
     * 路径规则集中在一处，测试也能拿临时目录自己造一个。
     */
    @Bean
    public NoteFilePaths noteFilePaths() {
        return new NoteFilePaths(markdownRoot());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() throws IOException {
        for (Path p : new Path[]{
                dataRoot(), markdownRoot(), attachmentRoot(),
                Paths.get(dataRoot, "logs")
        }) {
            Files.createDirectories(p);
        }
        // 不再预建 daily/ templates/：卡片 md 平铺在笔记目录根目录，靠 meta 里的 type 区分
        // 每日笔记与模板，目录不再承担这个语义。
    }
}
