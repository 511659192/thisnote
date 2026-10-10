package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.domain.NoteMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入链数（别人引用我）随 meta 一起维护：写路径增量、先写链接后建笔记的补解析、删除时释放。
 *
 * <p>列表页因此**完全不碰链接图**：它要的 ⇄ 两个数字都在 meta 里。
 */
class NoteInboundCountTest {

    @TempDir
    Path root;

    private NoteRepository store;
    private NoteService noteService;
    private LinkRepository links;

    @BeforeEach
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        store = new NoteRepository(config);
        NoteRepository noteRepository = store;
        CardRepository blockRepository = new CardRepository(store);
        TagRepository tagRepository = new TagRepository(store);
        NotebookRepository notebookRepository = new NotebookRepository(store);
        OpsRepository opsRepository = new OpsRepository(store);
        links = Mockito.mock(LinkRepository.class);
        noteService = new NoteService(noteRepository, tagRepository, links, notebookRepository,
                blockRepository, opsRepository);
    }

    private String newNote(String title, String contentMd) {
        return noteService.create(new Dtos.CreateNodeRequest(
                title, contentMd, "note", null, null, null, null, null));
    }

    private NoteMeta metaOf(String title) {
        for (NoteMeta meta : store.getAll()) {
            if (meta.title().equals(title)) {
                return meta;
            }
        }
        throw new AssertionError("找不到笔记：" + title);
    }

    private NoteMeta summaryOf(String title) {
        for (NoteMeta row : noteService.list(NoteService.Query.defaults())) {
            if (row.title().equals(title)) {
                return row;
            }
        }
        throw new AssertionError("列表里没有：" + title);
    }

    @Test
    @DisplayName("引用已存在的笔记：对端入链 +1，列表直接读 meta 的两个数字")
    void linkingExistingNoteCountsInbound() {
        newNote("乙", "乙的正文");
        newNote("甲", "见 [[乙]]");

        assertThat(metaOf("乙").inboundCount()).isEqualTo(1);
        assertThat(metaOf("乙").links()).isEmpty();
        // 入链清单随写落盘：详情页的引用面板直接读它（引用行原文 + 引用者 id）
        assertThat(metaOf("乙").inbound()).singleElement().satisfies(entry -> {
            assertThat(entry.fromId()).isEqualTo(metaOf("甲").id());
            assertThat(entry.label()).isEmpty();
            assertThat(entry.context()).contains("[[乙]]");
        });

        NoteMeta jia = summaryOf("甲");
        assertThat(jia.outboundCount()).isEqualTo(1);
        assertThat(jia.inboundCount()).isZero();
        assertThat(summaryOf("乙").inboundCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("先写链接、后建笔记：新建时把待解析的出链补上并计一次入链")
    void linkBeforeTargetExistsIsHealed() {
        newNote("甲", "以后会有 [[未来笔记]]");
        assertThat(metaOf("甲").unresolvedCount()).isEqualTo(1);
        assertThat(metaOf("甲").outboundCount()).isEqualTo(1);

        newNote("未来笔记", "刚建的");

        assertThat(metaOf("未来笔记").inboundCount()).isEqualTo(1);
        assertThat(metaOf("甲").unresolvedCount()).isZero();
        assertThat(metaOf("甲").links().get(0).to()).isNotNull();
    }

    @Test
    @DisplayName("把链接从正文里去掉：对端入链 -1")
    void removingTheLinkDecrements() {
        String b = newNote("乙", "乙的正文");
        String a = newNote("甲", "见 [[乙]]");
        assertThat(metaOf("乙").inboundCount()).isEqualTo(1);

        noteService.update(a, new Dtos.UpdateNodeRequest(null, "不再引用谁", null, null,
                null, null, null));
        System.out.println("[DEBUGOUT] 甲.meta=" + store.getOne(a));
        System.out.println("[DEBUGOUT] 乙.meta=" + store.getOne(b));

        assertThat(metaOf("乙").inboundCount()).isZero();
        assertThat(b).isNotBlank();
    }

    @Test
    @DisplayName("删掉引用者：被引用方的入链 -1")
    void deletingTheReferrerDecrements() {
        String b = newNote("乙", "乙的正文");
        String a = newNote("甲", "见 [[乙]]");
        assertThat(metaOf("乙").inboundCount()).isEqualTo(1);

        noteService.delete(a);

        assertThat(metaOf("乙").inboundCount()).isZero();
        assertThat(store.getOne(b)).isNotNull();
    }

    @Test
    @DisplayName("列表不碰链接图：⇄ 全部来自 meta")
    void listDoesNotTouchLinkGraph() {
        newNote("乙", "乙的正文");
        newNote("甲", "见 [[乙]]");

        Mockito.clearInvocations(links);
        noteService.list(NoteService.Query.defaults());

        Mockito.verifyNoInteractions(links);
    }

}
