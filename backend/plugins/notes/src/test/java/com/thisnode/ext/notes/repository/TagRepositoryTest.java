package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.domain.TagBook;
import com.thisnode.ext.notes.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签台账（{@code .notes/tags.json}）：一个文件装全部标签，归属仍写在笔记 meta 里。
 *
 * <p>不启 Spring：{@link NoteRepository} 只依赖 markdown 根目录，用临时目录直接跑。
 */
class TagRepositoryTest {

    @TempDir
    Path root;

    private NoteRepository store;
    private TagRepository tags;
    private NoteService service;

    @BeforeEach
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        tags = new TagRepository(store.paths());
        // 标签的归属在 meta（NoteRepository）、属性在台账（TagRepository）：跨两边的操作由服务编排。
        service = new NoteService(store, tags, new LinkRepository(store),
                new NotebookRepository(store), new CardRepository(store));
    }

    private void seedNote(String id, String... tagNames) {
        NoteMeta meta = NoteMeta.newNoteMeta(id, "笔记 " + id, "note", null, 1L);
        store.writeNoteMeta(meta.withTags(List.of(tagNames)));
    }

    private TagBook ledger() throws IOException {
        return store.readMeta(store.paths().tagsFile(), TagBook.class);
    }

    @Test
    @DisplayName("标签属性写在台账里，读取时直接拿到颜色")
    void keepsTagAttributesInLedger() throws IOException {
        seedNote("n1", "方法论");

        tags.setColor("方法论", "#3b82f6");

        List<Dtos.TagDto> one = service.tagsOf("n1");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).color()).isEqualTo("#3b82f6");
        assertThat(ledger().find("方法论").color()).isEqualTo("#3b82f6");
    }

    @Test
    @DisplayName("加标签会把名字登记进台账")
    void registersTagsOnWrite() throws IOException {
        seedNote("n1");

        service.addTag("n1", "#新标签");

        assertThat(store.getOne("n1").tags()).containsExactly("新标签");
        assertThat(ledger().find("新标签")).isNotNull();
        assertThat(ledger().tags()).hasSize(1);
    }

    @Test
    @DisplayName("改名：台账条目改名并保留颜色，笔记里的归属跟着改")
    void renamesTagAndMemberships() throws IOException {
        seedNote("n1", "设计");
        tags.setColor("设计", "#ff0000");

        service.renameTag("设计", "设计原则");

        assertThat(store.getOne("n1").tags()).containsExactly("设计原则");
        assertThat(ledger().find("设计")).isNull();
        assertThat(ledger().find("设计原则").color()).isEqualTo("#ff0000");
        assertThat(service.tagCatalog()).extracting(Dtos.TagDto::name).containsExactly("设计原则");
    }

    @Test
    @DisplayName("清单包含台账里还没人用的标签（计数 0），以及只在笔记里的标签")
    void listsLedgerTagsAndNoteTags() {
        seedNote("n1", "笔记里的");

        tags.ensure("还没人用");

        assertThat(service.tagCatalog())
                .extracting(Dtos.TagDto::name)
                .containsExactly("笔记里的", "还没人用");
        assertThat(service.tagCatalog())
                .extracting(Dtos.TagDto::count)
                .containsExactly(1, 0);
    }
}
