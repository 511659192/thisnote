package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.store.BlockMeta;
import com.thisnode.ext.notes.store.CardFile;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteFilePaths;
import com.thisnode.ext.notes.store.NoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库层在文件权威模型下的行为：正文落成卡片、软删除不动文件、硬删除清干净、
 * 标签/别名/归属写进笔记 meta。全部不启 Spring，只在临时目录上跑。
 *
 * <p>链接仓库在这里是 mock：链接仍是最后一块没搬到文件的领域，本轮不涉及它的行为。
 */
class NoteRepositoryTest {

    @TempDir
    Path root;

    private NoteCompositionService composition;
    private NoteStore store;
    private NoteRepository noteRepository;
    private NoteBlockRepository blockRepository;
    private TagRepository tagRepository;
    private NotebookRepository notebookRepository;
    private NoteService noteService;

    @BeforeEach
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        store = new NoteStore(config);
        composition = TestWiring.composition(store);

        noteRepository = new NoteRepository(store, TestWiring.composition(store));
        blockRepository = new NoteBlockRepository(TestWiring.composition(store));
        tagRepository = new TagRepository(store);
        notebookRepository = new NotebookRepository(store);
        OpsRepository opsRepository = new OpsRepository(store);
        LinkRepository links = Mockito.mock(LinkRepository.class);
        noteService = new NoteService(noteRepository, tagRepository, links, notebookRepository,
                blockRepository, opsRepository, store, TestWiring.composition(store));
    }

    private String newNote(String title, String contentMd) {
        Note n = new Note();
        n.title = title;
        n.contentMd = contentMd;
        n.type = "note";
        return noteRepository.insert(n);
    }

    @Test
    @DisplayName("新建笔记时正文落成一张卡片，读回来是卡片拼接的结果")
    void contentBecomesACard() {
        String id = newNote("会议记录", "# 会议记录\n\n今天讨论了三件事。");

        Note read = noteRepository.findById(id);
        assertThat(read.title).isEqualTo("会议记录");
        assertThat(read.contentMd).isEqualTo("# 会议记录\n\n今天讨论了三件事。");

        List<BlockMeta> cards = composition.blocks(id);
        assertThat(cards).hasSize(1);
        assertThat(Files.exists(composition.fileOf(cards.get(0).id()))).isTrue();
    }

    @Test
    @DisplayName("软删除只改标记、卡片文件还在；硬删除把文件一起清掉")
    void trashKeepsFilesAndPurgeRemovesThem() throws IOException {
        String id = newNote("要删的笔记", "内容还在");
        String cardId = composition.blocks(id).get(0).id();
        Path card = composition.fileOf(cardId);
        assertThat(Files.exists(card)).isTrue();
        assertThat(Files.readString(card, java.nio.charset.StandardCharsets.UTF_8))
                .startsWith(CardFile.META_PREFIX)
                .contains(cardId);

        noteRepository.setDeleted(id, true);
        assertThat(noteRepository.findById(id).deleted).isTrue();
        assertThat(Files.exists(card)).isTrue();

        noteRepository.setDeleted(id, false);
        assertThat(noteRepository.findById(id).deleted).isFalse();

        noteRepository.setDeleted(id, true);
        noteRepository.deleteHard(id);
        assertThat(Files.exists(card)).isFalse();
        assertThat(noteRepository.findById(id)).isNull();
        assertThat(composition.blocks(id)).isEmpty();
        assertThat(Files.exists(root.resolve(".notes").resolve(id + ".json"))).isFalse();
    }

    @Test
    @DisplayName("标签与别名写在笔记 meta 里，标签 id 就是它的名字")
    void tagsAndAliasesLiveInNoteMeta() {
        String id = newNote("带标签的笔记", "正文");

        tagRepository.setNodeTags(id, List.of("#设计", "随笔", "设计"));
        assertThat(noteService.tagsOfNames(id)).containsExactly("设计", "随笔");
        assertThat(store.note(id).tags()).containsExactly("设计", "随笔");

        tagRepository.setColor("设计", "#ff0000");
        assertThat(tagRepository.tagsOf(id)).anySatisfy(tag -> {
            assertThat(tag.name()).isEqualTo("设计");
            assertThat(tag.color()).isEqualTo("#ff0000");
            assertThat(tag.id()).isEqualTo("设计");
        });
        assertThat(tagRepository.listWithCounts()).hasSize(2).anySatisfy(tag -> {
            assertThat(tag.name()).isEqualTo("设计");
            assertThat(tag.count()).isEqualTo(1);
        });

        noteService.setAliases(id, List.of("别名一", "别名二"));
        assertThat(noteService.aliasesOf(id)).containsExactly("别名一", "别名二");
        assertThat(noteService.resolveTitle("别名一")).isEqualTo(id);
        assertThat(noteService.resolveTitle("带标签的笔记")).isEqualTo(id);

        tagRepository.removeNodeTag(id, "设计");
        assertThat(noteService.tagsOfNames(id)).containsExactly("随笔");
    }

    @Test
    @DisplayName("归属分组写在笔记 meta 上，移动笔记只改那一个文件")
    void notebookAssignmentIsStoredOnTheNote() {
        String id = newNote("要归组的笔记", "正文");
        String group = notebookRepository.ensureDefaultGroup();
        notebookRepository.addNodeToNotebook(id, group);

        assertThat(store.note(id).notebookId()).isEqualTo(group);
        assertThat(notebookRepository.nodeIdsOf(group)).containsExactly(id);
        assertThat(notebookRepository.list("notebook")).singleElement()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1));

        String other = notebookRepository.create("工作", null, "💼", "notebook");
        notebookRepository.setExclusiveGroup(id, other);
        assertThat(store.note(id).notebookId()).isEqualTo(other);
        assertThat(notebookRepository.nodeIdsOf(group)).isEmpty();
        assertThat(notebookRepository.countNotebooksOf(id)).isEqualTo(1);

        // 删分组不能让笔记变成无归属
        notebookRepository.delete(other);
        assertThat(store.note(id).notebookId()).isEqualTo(NotebookRepository.DEFAULT_GROUP_ID);
    }

    @Test
    @DisplayName("列表过滤（回收站 / 归档 / 标签 / 全文）在内存索引上等价于旧的 SQL 条件")
    void listFiltersMatchTheOldSql() {
        String a = newNote("苹果笔记", "关于苹果的正文");
        String b = newNote("香蕉笔记", "关于香蕉的正文");
        assertThat(noteService.list(NoteService.Query.defaults())).hasSize(2);

        assertThat(noteService.list(new NoteService.Query("all", null, null, null, "苹果",
                false, false, 500, 0)))
                .extracting(s -> s.id()).containsExactly(a);
        assertThat(noteService.list(new NoteService.Query("all", null, null, null, "香蕉",
                false, false, 500, 0)))
                .extracting(s -> s.id()).containsExactly(b);

        tagRepository.setNodeTags(a, List.of("水果"));
        assertThat(noteService.list(new NoteService.Query("all", "水果", null, null, null,
                false, false, 500, 0)))
                .extracting(s -> s.id()).containsExactly(a);

        noteRepository.setArchived(b, true);
        assertThat(noteService.list(NoteService.Query.defaults())).extracting(s -> s.id())
                .containsExactly(a);
        assertThat(noteService.list(new NoteService.Query("all", null, null, null, null,
                true, false, 500, 0))).hasSize(2);
        assertThat(noteService.list(new NoteService.Query("archived", null, null, null, null,
                false, false, 500, 0))).extracting(s -> s.id()).containsExactly(b);

        noteRepository.setDeleted(b, true);
        assertThat(noteService.list(new NoteService.Query("trash", null, null, null, null,
                false, false, 500, 0))).extracting(s -> s.id()).containsExactly(b);
        assertThat(noteService.counts()).containsEntry("trash", 1).containsEntry("all", 1);
    }
}
