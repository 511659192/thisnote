package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

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

    private NoteCompositionService composition;
    private NoteRepository nodes;
    private TagRepository tags;
    private LinkRepository links;
    private NoteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        NoteStore store = new NoteStore(config);
        composition = TestWiring.composition(store);

        nodes = new NoteRepository(store, TestWiring.composition(store));
        NoteBlockRepository blocks = new NoteBlockRepository(TestWiring.composition(store));
        tags = new TagRepository(store);
        NotebookRepository notebooks = new NotebookRepository(store);
        OpsRepository ops = new OpsRepository(store);
        links = new LinkRepository(store, TestWiring.composition(store));
        service = new NoteService(nodes, tags, links, notebooks, blocks, ops,
                store, TestWiring.composition(store));

    }

    private String newNote(String title, String contentMd) {
        Note n = new Note();
        n.title = title;
        n.contentMd = contentMd;
        n.type = "note";
        return nodes.insert(n);
    }

    @Test
    @DisplayName("链接完全从卡片正文投影：解析的、未解析的都在图上")
    void linksAreDerivedFromCardContent() {
        String target = newNote("目标笔记", "目标正文");
        String source = newNote("来源笔记", "见 [[目标笔记]] 与 [[还没有的笔记]]");

        List<LinkRepository.LinkRow> out = links.outbound(source);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).toNodeId()).isEqualTo(target);
        assertThat(out.get(1).toNodeId()).isNull();
        assertThat(out.get(1).toTitle()).isEqualTo("还没有的笔记");

        assertThat(links.inbound(target)).hasSize(1);
        assertThat(links.backlinks(target)).singleElement()
                .satisfies(back -> assertThat(back.noteId()).isEqualTo(source));
        assertThat(links.total()).isEqualTo(2);
        assertThat(links.stubExists("还没有的笔记")).isTrue();
        assertThat(links.listStubs()).extracting(m -> m.get("title"))
                .containsExactly("还没有的笔记");
    }

    @Test
    @DisplayName("后建的目标笔记让悬空链接自动解析，不需要任何修复调用")
    void creatingTheTargetResolvesTheLink() {
        String source = newNote("来源", "关于 [[以后才写的]] 的想法");
        assertThat(links.outbound(source).get(0).toNodeId()).isNull();

        String target = service.create(new Dtos.CreateNodeRequest(
                "以后才写的", "", "note", null, null, null, null, null, null));

        assertThat(links.outbound(source).get(0).toNodeId()).isEqualTo(target);
        assertThat(links.stubExists("以后才写的")).isFalse();
    }

    @Test
    @DisplayName("重命名会改写引用它的卡片正文，链接跟着走")
    void renamingRewritesLinkingCards() {
        String target = newNote("旧标题", "目标正文");
        String source = newNote("来源", "引用 [[旧标题]] 的正文");

        service.patch(target, new Dtos.UpdateNodeRequest(
                "新标题", null, null, null, null, null, null, null, null));

        assertThat(composition.noteMarkdown(source)).contains("[[新标题]]");
        assertThat(composition.noteMarkdown(source)).doesNotContain("[[旧标题]]");
        assertThat(links.outbound(source).get(0).toNodeId()).isEqualTo(target);
    }

}
