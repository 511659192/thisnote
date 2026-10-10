package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.domain.CardMeta;
import com.thisnode.ext.notes.domain.CardFile;
import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库层在文件权威模型下的行为：正文落成卡片、软删除不动文件、硬删除清干净、
 * 标签/归属写进笔记 meta。全部不启 Spring，只在临时目录上跑。
 *
 * <p>链接仓库在这里是 mock：链接仍是最后一块没搬到文件的领域，本轮不涉及它的行为。
 */
class NoteRepositoryTest {

    @TempDir
    Path root;

    private NoteRepository store;
    private CardRepository cardRepository;
    private NoteRepository noteRepository;
    private CardRepository blockRepository;
    private TagRepository tagRepository;
    private NotebookRepository notebookRepository;
    private NoteService noteService;

    @BeforeEach
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        cardRepository = new CardRepository(store);

        noteRepository = store;
        blockRepository = new CardRepository(store);
        tagRepository = new TagRepository(store.paths());
        notebookRepository = new NotebookRepository(store);
        noteService = new NoteService(noteRepository, tagRepository, notebookRepository, blockRepository);
    }

    private String newGetOne(String title, String contentMd) {
        String id = noteRepository.insert(title, "note");
        // 首卡编排（NoteService.create 同款）：新架构下 insert 只写 meta。
        cardRepository.putCard(id, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null,
                System.currentTimeMillis()), contentMd);
        return id;
    }

    @Test
    @DisplayName("新建笔记时正文落成一张卡片，读回来是卡片拼接的结果")
    void contentBecomesACard() {
        String id = newGetOne("会议记录", "# 会议记录\n\n今天讨论了三件事。");

        assertThat(noteRepository.getOne(id).getTitle()).isEqualTo("会议记录");
        assertThat(noteRepository.noteMarkdown(id)).isEqualTo("# 会议记录\n\n今天讨论了三件事。");

        List<CardMeta> cards = store.cards(id);
        assertThat(cards).hasSize(1);
        assertThat(Files.exists(store.fileOf(cards.get(0).id()))).isTrue();
    }

    @Test
    @DisplayName("硬删除把卡片文件与 meta 一起清掉")
    void deleteRemovesEveryFile() throws IOException {
        String id = newGetOne("要删的笔记", "内容还在");
        String cardId = store.cards(id).get(0).id();
        Path card = store.fileOf(cardId);
        assertThat(Files.exists(card)).isTrue();
        assertThat(Files.readString(card, java.nio.charset.StandardCharsets.UTF_8))
                .startsWith(CardFile.META_PREFIX)
                .contains(cardId);

        noteRepository.delete(id);
        assertThat(Files.exists(card)).isFalse();
        assertThat(noteRepository.getOne(id)).isNull();
        assertThat(store.cards(id)).isEmpty();
        assertThat(Files.exists(root.resolve("meta").resolve(id + ".json"))).isFalse();
    }

    @Test
    @DisplayName("标签写在笔记 meta 里，标签 id 就是它的名字")
    void tagsLiveInGetOneMeta() {
        String id = newGetOne("带标签的笔记", "正文");

        store.setNodeTags(id, List.of("#设计", "随笔", "设计"));
        assertThat(noteService.tagsOfNames(id)).containsExactly("设计", "随笔");
        assertThat(store.getOne(id).getTags()).containsExactly("设计", "随笔");

        tagRepository.setColor("设计", "#ff0000");
        assertThat(noteService.tagsOf(id)).anySatisfy(tag -> {
            assertThat(tag.name()).isEqualTo("设计");
            assertThat(tag.color()).isEqualTo("#ff0000");
            assertThat(tag.id()).isEqualTo("设计");
        });
        assertThat(noteService.tagCatalog()).hasSize(2).anySatisfy(tag -> {
            assertThat(tag.name()).isEqualTo("设计");
            assertThat(tag.count()).isEqualTo(1);
        });

        assertThat(noteService.resolveTitle("带标签的笔记")).isEqualTo(id);

        store.removeNodeTag(id, "设计");
        assertThat(noteService.tagsOfNames(id)).containsExactly("随笔");
    }

    @Test
    @DisplayName("归属分组写在笔记 meta 上，移动笔记只改那一个文件")
    void notebookAssignmentIsStoredOnTheGetOne() {
        String id = newGetOne("要归组的笔记", "正文");
        String group = notebookRepository.ensureDefaultGroup();
        notebookRepository.addNodeToNotebook(id, group);

        assertThat(store.getOne(id).getNotebookId()).isEqualTo(group);
        assertThat(notebookRepository.nodeIdsOf(group)).containsExactly(id);
        assertThat(notebookRepository.list("notebook")).singleElement()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1));

        String other = notebookRepository.create("工作", null, "💼", "notebook");
        notebookRepository.setExclusiveGroup(id, other);
        assertThat(store.getOne(id).getNotebookId()).isEqualTo(other);
        assertThat(notebookRepository.nodeIdsOf(group)).isEmpty();

        // 删分组不能让笔记变成无归属
        notebookRepository.delete(other);
        assertThat(store.getOne(id).getNotebookId()).isEqualTo(NotebookRepository.DEFAULT_GROUP_ID);
    }

    @Test
    @DisplayName("列表过滤（标签 / 全文）在内存过滤上等价于旧的 SQL 条件")
    void listFiltersMatchTheOldSql() {
        String a = newGetOne("苹果笔记", "关于苹果的正文");
        String b = newGetOne("香蕉笔记", "关于香蕉的正文");
        assertThat(noteService.list(NoteService.Query.defaults())).hasSize(2);

        assertThat(noteService.list(new NoteService.Query(null, null, null, "苹果", 500, 0)))
                .extracting(s -> s.getId()).containsExactly(a);
        assertThat(noteService.list(new NoteService.Query(null, null, null, "香蕉", 500, 0)))
                .extracting(s -> s.getId()).containsExactly(b);

        store.setNodeTags(a, List.of("水果"));
        assertThat(noteService.list(new NoteService.Query("水果", null, null, null, 500, 0)))
                .extracting(s -> s.getId()).containsExactly(a);
    }
}
