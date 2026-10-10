package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.CardMeta;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链接图与搜索在文件权威模型下的行为。
 *
 * <p>两者都是**派生数据**：链接从卡片正文投影，搜索索引就是内存里的笔记 —— 所以这里的重点不是
 * "写进去能读出来"，而是"正文一变，链接与搜索自动就是新的"。
 */
class NoteLinkSearchTest {

    @TempDir
    Path root;

    private NoteRepository nodes;
    private NoteRepository store;
    private CardRepository cardRepository;
    private TagRepository tags;
    private NoteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        cardRepository = new CardRepository(store);

        nodes = store;
        CardRepository blocks = new CardRepository(store);
        tags = new TagRepository(store.paths());
        NotebookRepository notebooks = new NotebookRepository(store);
        service = new NoteService(nodes, tags, notebooks, blocks);

    }

    private String newNote(String title, String contentMd) {
        String id = nodes.insert(title, "note");
        // 首卡编排（NoteService.create 同款）：新架构下 insert 只写 meta。
        cardRepository.putCard(id, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null,
                Instant.now().toEpochMilli()), contentMd);
        return id;
    }

    @Test
    @DisplayName("链接完全从卡片正文投影：谁引用了我、哪些还没解析，都是现算的")
    void linksAreDerivedFromCardContent() {
        String target = newNote("目标笔记", "目标正文");
        String source = newNote("来源笔记", "见 [[目标笔记]] 与 [[还没有的笔记]]");

        assertThat(service.detail(source).unresolved()).containsExactly("还没有的笔记");
        assertThat(service.detail(target).unresolved()).isEmpty();
    }

    @Test
    @DisplayName("后建的目标笔记让悬空链接自动解析，不需要任何修复调用")
    void creatingTheTargetResolvesTheLink() {
        String source = newNote("来源", "关于 [[以后才写的]] 的想法");
        assertThat(service.detail(source).unresolved()).containsExactly("以后才写的");

        String target = service.create(new Dtos.CreateNodeRequest(
                "以后才写的", "", "note", null, null));

        assertThat(service.detail(source).unresolved()).isEmpty();
        assertThat(service.detail(target).unresolved()).isEmpty();
    }

    @Test
    @DisplayName("重命名会改写引用它的卡片正文，链接跟着走")
    void renamingRewritesLinkingCards() {
        String target = newNote("旧标题", "目标正文");
        String source = newNote("来源", "引用 [[旧标题]] 的正文");

        service.update(target, new Dtos.UpdateNodeRequest(
                "新标题", null, null, null, null, null, null));

        assertThat(store.noteMarkdown(source)).contains("[[新标题]]");
        assertThat(store.noteMarkdown(source)).doesNotContain("[[旧标题]]");
    }

}
