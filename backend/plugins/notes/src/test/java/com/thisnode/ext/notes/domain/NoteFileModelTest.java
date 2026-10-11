package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.service.StartupMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件权威模型的基本规则：认领、聚合、往返、删除一致性。
 *
 * <p>这一组打在 {@link NoteRepository} 与
 * {@link CardRepository} 上。因为读取改成"按需现扫"，断言里不再有
 * {@code load()}/{@code reload()} 这一步 —— 写完就能读到，读到的一定是磁盘现状。
 *
 * <p>这些测试不启 Spring —— {@link NoteRepository} 只依赖 markdown 根目录，用临时目录直接跑，
 * 比拉一个应用上下文快得多，也避免测试之间互相污染真实笔记目录。
 */
class NoteFileModelTest {

    @TempDir
    Path root;

    private NoteRepository store;
    private CardRepository cardRepository;
    private StartupMaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        cardRepository = new CardRepository(store);
        maintenance = new StartupMaintenanceService(store);
        maintenance.run();
    }

    @Test
    @DisplayName("没有 meta 的 md 被认领成一篇单卡笔记，并生成两份 meta")
    void claimsMdWithoutMeta() throws IOException {
        Path md = root.resolve("cardRefs").resolve("我的第一篇笔记.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, "# 会议记录\n\n今天讨论了三件事。", StandardCharsets.UTF_8);

        // 启动维护：认领未纳管的 md
        maintenance.run();

        assertThat(store.findAll()).hasSize(1);
        NoteMeta note = store.findAll().get(0);
        assertThat(note.getTitle()).isEqualTo("会议记录");

        // 笔记 meta 落在 meta/ 下
        assertThat(Files.exists(root.resolve("meta").resolve(note.getId() + ".json"))).isTrue();

        // 一张卡：文件原地不动，meta 写在它的头部注释里
        List<CardMeta> cards = store.cards(note.getId());
        assertThat(cards).hasSize(1);
        assertThat(store.noteIdOf(cards.get(0).id())).isEqualTo(note.getId());
        assertThat(store.content(cards.get(0).id())).contains("今天讨论了三件事");
        assertThat(Files.readString(md, StandardCharsets.UTF_8))
                .startsWith(CardFile.META_PREFIX)
                .contains(cards.get(0).id());
        assertThat(store.fileOf(cards.get(0).id())).isEqualTo(md);
    }

    @Test
    @DisplayName("卡片按清单顺序聚合成一篇完整笔记")
    void aggregatesBlocksInZOrder() throws IOException {
        String noteId = "note-1";
        writeNoteMeta(NoteMeta.newNoteMeta(noteId, "组合笔记", "note", null));
        writeCard("first", "card-b", noteId, 1, "第二段");
        writeCard("second", "card-a", noteId, 0, "第一段");

        List<CardMeta> cards = store.cards(noteId);
        assertThat(cards).extracting(CardMeta::id).containsExactly("card-a", "card-b");
        assertThat(store.noteMarkdown(noteId)).isEqualTo("第一段\n\n第二段");
    }

    @Test
    @DisplayName("删除笔记时它的卡片 md 与笔记 meta 一起消失，邻居不受影响")
    void purgeRemovesEveryOwnedFile() {
        String now = "note-now";
        String other = "note-other";
        writeNoteMeta(NoteMeta.newNoteMeta(now, "要删的", "note", null));
        writeNoteMeta(NoteMeta.newNoteMeta(other, "留下的", "note", null));
        cardRepository.putCard(now, CardMeta.newCardMeta("b1", 0, 0, "卡片一", null, null, 1L), "内容一");
        cardRepository.putCard(now, CardMeta.newCardMeta("b2", 0, 300, "卡片二", null, null, 2L), "内容二");
        cardRepository.putCard(other, CardMeta.newCardMeta("b3", 0, 0, "别人的卡", null, null, 3L), "别的内容");

        Path b1 = store.fileOf("b1");
        Path b2 = store.fileOf("b2");
        Path b3 = store.fileOf("b3");
        assertThat(List.of(b1, b2, b3)).allSatisfy(p -> assertThat(Files.exists(p)).isTrue());

        store.delete(now);

        assertThat(Files.exists(b1)).isFalse();
        assertThat(Files.exists(b2)).isFalse();
        assertThat(Files.exists(root.resolve("meta").resolve(now + ".json"))).isFalse();

        assertThat(store.getOneNoteMeta(now)).isNull();
        assertThat(store.cards(now)).isEmpty();
        assertThat(store.card("b1")).isNull();

        assertThat(store.getOneNoteMeta(other)).isNotNull();
        assertThat(Files.exists(b3)).isTrue();
        assertThat(store.noteMarkdown(other)).isEqualTo("别的内容");
    }

    @Test
    @DisplayName("正文、几何与插件扩展区都能从磁盘原样读回")
    void roundTripsThroughDisk() {
        NoteMeta noteMeta = NoteMeta.newNoteMeta("n1", "往返", "note", "grp-default");
        noteMeta.setTags(List.of("标签一"));
        writeNoteMeta(noteMeta);
        cardRepository.putCard("n1", new CardMeta(CardMeta.SCHEMA, "b1", "卡片标题",
                12.5, 340.25, 500, 200, 3, "#ff0000", "doing", true, Map.of(), 10L, 10L),
                "正文内容");
        store.putExtension("b1", "memory-curve", Map.of("state", "review", "reps", 2));

        NoteMeta note = store.getOneNoteMeta("n1");
        assertThat(note.getTitle()).isEqualTo("往返");
        assertThat(note.getTags()).containsExactly("标签一");
        assertThat(note.getNotebookId()).isEqualTo("grp-default");

        CardMeta card = store.card("b1");
        assertThat(card.title()).isEqualTo("卡片标题");
        assertThat(card.x()).isEqualTo(12.5);
        assertThat(card.y()).isEqualTo(340.25);
        assertThat(card.w()).isEqualTo(500);
        assertThat(card.z()).isEqualTo(3);
        assertThat(card.color()).isEqualTo("#ff0000");
        assertThat(card.status()).isEqualTo("doing");
        assertThat(card.collapsed()).isTrue();
        assertThat(store.content("b1")).isEqualTo("正文内容");

        assertThat(store.extension("b1", "memory-curve"))
                .containsEntry("state", "review")
                .containsEntry("reps", 2);
    }

    @Test
    @DisplayName("两张同名卡片落在两个文件里，不会互相覆盖")
    void disambiguatesSameSlug() {
        writeNoteMeta(NoteMeta.newNoteMeta("n1", "同名测试", "note", null));
        cardRepository.putCard("n1", CardMeta.newCardMeta("bbbbbbbb-1111", 0, 0, "", null, null, 1L), "第一张");
        cardRepository.putCard("n1", CardMeta.newCardMeta("bbbbbbbb-2222", 0, 300, "", null, null, 2L), "第二张");

        Path first = store.fileOf("bbbbbbbb-1111");
        Path second = store.fileOf("bbbbbbbb-2222");
        assertThat(first).isNotEqualTo(second);
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.exists(second)).isTrue();
        assertThat(store.cards("n1")).hasSize(2);
    }

    @Test
    @DisplayName("笔记 meta 丢失时卡片仍在磁盘上，会被认领成一篇独立笔记")
    void orphanCardIsAdoptedAsItsOwnNote() throws IOException {
        writeCard("only", "card-x", "note-lost", 0, "孤立卡片的内容");
        Files.deleteIfExists(root.resolve("meta").resolve("note-lost.json"));

        assertThat(store.getOneNoteMeta("note-lost")).isNull();
        assertThat(store.findAll()).isEmpty();

        // 对账做的事：发现清单里没有这个文件 → 认领成一篇新笔记
        int applied = store.claimUnmanaged();

        assertThat(applied).isEqualTo(1);
        assertThat(store.findAll()).hasSize(1);
        assertThat(store.allCards()).hasSize(1);
        assertThat(store.content("card-x")).isEqualTo("孤立卡片的内容");
        assertThat(Files.exists(root.resolve("meta")
                .resolve(store.findAll().get(0).getId() + ".json"))).isTrue();
    }

    @Test
    @DisplayName("meta 存卡片文件路径，仍能通过 CardMeta.id 管理卡片")
    void readsCardPathsFromNoteMeta() throws IOException {
        Path card = store.writeCard("旧笔记", CardMeta.newCardMeta("card-legacy", 0, 0, "", null, null, 1L),
                "旧格式中的正文", null);
        Path metaDir = root.resolve("meta");
        Files.createDirectories(metaDir);
        String legacyMeta = com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "v", 2, "id", "note-legacy", "title", "旧笔记", "type", "note",
                "cardPath", List.of(store.paths().relative(card)),
                "createdAt", 1, "updatedAt", 1));
        Files.writeString(metaDir.resolve("note-legacy.json"), legacyMeta, StandardCharsets.UTF_8);

        NoteMeta loaded = store.getOneNoteMeta("note-legacy");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getCardPaths()).hasSize(1);
        assertThat(store.noteMarkdown("note-legacy")).isEqualTo("旧格式中的正文");
    }

    @Test
    @DisplayName("台账文件不会被当成笔记读：没有 id 的 json 一律跳过")
    void ledgerFilesAreNotNotes() throws IOException {
        Path ledgers = root.resolve("ledgers");
        Path noteMetas = root.resolve("meta");
        Files.createDirectories(ledgers);
        Files.createDirectories(noteMetas);
        Files.writeString(ledgers.resolve("notebooks.json"),
                "{\"v\":1,\"notebooks\":[{\"v\":1,\"id\":\"grp-default\",\"type\":\"notebook\","
                        + "\"title\":\"收集箱\",\"parentId\":null,\"icon\":null,\"sortOrder\":0,"
                        + "\"createdAt\":1,\"updatedAt\":1}]}");
        Files.writeString(ledgers.resolve("tags.json"), "{\"设计\":\"#ff0000\"}");
        Files.writeString(ledgers.resolve("settings.json"), "{\"device_id\":\"x\"}");
        Files.writeString(noteMetas.resolve("random.json"), "{\"title\":\"手写但没有 id\"}");

        assertThat(store.findAll()).isEmpty();
    }

    @Test
    @DisplayName("非 md 文件不会被认领成笔记")
    void nonMarkdownFileIsNotClaimed() throws IOException {
        Path stray = root.resolve("早就删掉的卡片-deadbeef.meta.json");
        Files.writeString(stray, "{\"v\":1,\"id\":\"deadbeef-1111\",\"noteId\":\"gone\"}",
                StandardCharsets.UTF_8);

        maintenance.run();

        assertThat(Files.exists(stray)).isTrue();
        assertThat(store.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void writeNoteMeta(NoteMeta note) {
        com.thisnode.ext.notes.util.JsonFileUtils.write(store.paths().noteMetaPath(note.getId()), note);
    }

    /** 造一张"磁盘上已有"的卡片：写 md（含头部注释）并把它挂到所属笔记的清单上。 */
    private void writeCard(String slug, String cardId, String noteId, int slot, String content)
            throws IOException {
        Path md = root.resolve("cardRefs").resolve(slug + "-" + NoteFilePaths.shortId(cardId) + ".md");
        Files.createDirectories(md.getParent());
        CardMeta card = new CardMeta(CardMeta.SCHEMA, cardId, "",
                0, slot * 300.0, CardMeta.DEFAULT_W, CardMeta.DEFAULT_H, slot,
                null, null, false, Map.of(), 1L, 1L);
        store.writeCard("测试笔记", card, content, md);
        NoteMeta note = com.thisnode.ext.notes.util.JsonFileUtils.read(store.paths().noteMetaPath(noteId), NoteMeta.class);
        if (note != null) {
            List<String> paths = new ArrayList<>(note.getCardPaths());
            paths.add(Math.max(0, Math.min(slot, paths.size())), store.paths().relative(md));
            note.setCardPaths(paths);
            com.thisnode.ext.notes.util.JsonFileUtils.write(store.paths().noteMetaPath(note.getId()), note);
        }
    }
}
