package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.domain.NoteMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表 / 详情只读笔记 meta：标签**名字**来自 meta，颜色与计数只走独立的 {@code GET /api/tags}。
 *
 * <p>最有力的证明就是这里的第一条用例：把标签台账（{@code .notes/tags.json}）写成坏文件，
 * 列表照样正常返回标签名 —— 说明这条路径根本不碰台账。
 */
class NoteListTagsTest {

    @TempDir
    Path root;

    private NoteService noteService;
    private TagRepository tagRepository;

    @BeforeEach
    void setUp() {
        NoteRepository store = new NoteRepository(new NoteFilePaths(root));
        NoteRepository noteRepository = store;
        CardRepository blockRepository = new CardRepository(store);
        tagRepository = new TagRepository(store.paths());
        NotebookRepository notebookRepository = new NotebookRepository(store);
        noteService = new NoteService(noteRepository, tagRepository, notebookRepository, blockRepository);
    }

    private String newNote(String title, List<String> tags) {
        return noteService.create(new Dtos.CreateNodeRequest(title, "", "note", tags, null));
    }

    @Test
    @DisplayName("台账坏了也不影响列表：标签名只从笔记 meta 读")
    void listReadsTagsFromMetaOnly() throws IOException {
        newNote("甲", List.of("设计", "方法论"));
        Path metaDir = root.resolve("meta");
        Files.createDirectories(metaDir);
        // 故意写坏台账（不是合法 JSON，也不是台账结构）
        Files.writeString(metaDir.resolve("tags.json"), "这不是 JSON {{{", StandardCharsets.UTF_8);

        List<NoteMeta> list = noteService.list(NoteService.Query.defaults());

        assertThat(list).hasSize(1);
        // 列表直接返回 meta，标签保持 meta 里的存储顺序（不再有列表侧排序）。
        assertThat(list.get(0).getTags()).containsExactly("设计", "方法论");
    }

    @Test
    @DisplayName("列表与详情返回的是标签名（颜色不在这两个响应里）")
    void listAndDetailCarryNamesOnly() {
        String id = newNote("乙", List.of("设计"));

        NoteMeta summary = noteService.list(NoteService.Query.defaults()).get(0);
        assertThat(summary.getTags()).containsExactly("设计");

        Dtos.NoteDetail detail = noteService.detail(id);
        assertThat(detail.tags()).containsExactly("设计");
    }

    @Test
    @DisplayName("标签目录仍带颜色与计数（独立接口的数据源）")
    void catalogKeepsColorsAndCounts() {
        newNote("丙", List.of("设计"));
        tagRepository.setColor("设计", "#ff0000");

        List<Dtos.TagDto> catalog = noteService.tagCatalog();

        assertThat(catalog).hasSize(1);
        assertThat(catalog.get(0).name()).isEqualTo("设计");
        assertThat(catalog.get(0).color()).isEqualTo("#ff0000");
        assertThat(catalog.get(0).count()).isEqualTo(1);
    }
}
