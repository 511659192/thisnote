package com.thisnode.ext.notes.store;

import com.thisnode.ext.notes.config.NotesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

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
 * <p>这一组打在 {@link NoteStore} 与
 * {@link NoteCompositionService} 上。因为读取改成"按需现扫"，断言里不再有
 * {@code load()}/{@code reload()} 这一步 —— 写完就能读到，读到的一定是磁盘现状。
 *
 * <p>这些测试不启 Spring —— {@link NoteStore} 只依赖 markdown 根目录，用临时目录直接跑，
 * 比拉一个应用上下文快得多，也避免测试之间互相污染真实笔记目录。
 */
class NoteFileModelTest {

    @TempDir
    Path root;

    private NoteStore store;
    private NoteCompositionService composition;
    private StartupMaintenance maintenance;

    @BeforeEach
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        store = new NoteStore(config);
        composition = new NoteCompositionService(store);
        maintenance = new StartupMaintenance(store, composition);
        maintenance.run();
    }

    @Test
    @DisplayName("没有 meta 的 md 被认领成一篇单卡笔记，并生成两份 meta")
    void claimsMdWithoutMeta() throws IOException {
        Path md = root.resolve("我的第一篇笔记.md");
        Files.writeString(md, "# 会议记录\n\n今天讨论了三件事。", StandardCharsets.UTF_8);

        // 启动维护：认领未纳管的 md
        maintenance.run();

        assertThat(store.scanNotes()).hasSize(1);
        NoteMeta note = store.scanNotes().get(0);
        assertThat(note.title()).isEqualTo("会议记录");

        // 笔记 meta 落在 .notes 下
        assertThat(Files.exists(root.resolve(".notes").resolve(note.id() + ".json"))).isTrue();

        // 一张卡：文件原地不动，meta 写进了它的头部注释，不再有 sidecar
        List<BlockMeta> blocks = composition.blocks(note.id());
        assertThat(blocks).hasSize(1);
        assertThat(composition.noteIdOf(blocks.get(0).id())).isEqualTo(note.id());
        assertThat(composition.content(blocks.get(0).id())).contains("今天讨论了三件事");
        assertThat(Files.exists(NoteFilePaths.sidecarFor(md))).isFalse();
        assertThat(Files.readString(md, StandardCharsets.UTF_8))
                .startsWith(CardFile.META_PREFIX)
                .contains(blocks.get(0).id());
        assertThat(composition.fileOf(blocks.get(0).id())).isEqualTo(md);
    }

    @Test
    @DisplayName("卡片按清单顺序聚合成一篇完整笔记")
    void aggregatesBlocksInZOrder() throws IOException {
        String noteId = "note-1";
        writeNoteMeta(NoteMeta.fresh(noteId, "组合笔记", "note", null, null, null, null, 1L));
        writeCard("first", "card-b", noteId, 1, "第二段");
        writeCard("second", "card-a", noteId, 0, "第一段");

        List<BlockMeta> blocks = composition.blocks(noteId);
        assertThat(blocks).extracting(BlockMeta::id).containsExactly("card-a", "card-b");
        assertThat(composition.noteMarkdown(noteId)).isEqualTo("第一段\n\n第二段");
    }

    @Test
    @DisplayName("删除笔记时它的卡片 md 与笔记 meta 一起消失，邻居不受影响")
    void purgeRemovesEveryOwnedFile() {
        String now = "note-now";
        String other = "note-other";
        store.writeNoteMeta(NoteMeta.fresh(now, "要删的", "note", null, null, null, null, 1L));
        store.writeNoteMeta(NoteMeta.fresh(other, "留下的", "note", null, null, null, null, 1L));
        composition.putBlock(now, BlockMeta.fresh("b1", 0, 0, "卡片一", null, null, 1L), "内容一");
        composition.putBlock(now, BlockMeta.fresh("b2", 0, 300, "卡片二", null, null, 2L), "内容二");
        composition.putBlock(other, BlockMeta.fresh("b3", 0, 0, "别人的卡", null, null, 3L), "别的内容");

        Path b1 = composition.fileOf("b1");
        Path b2 = composition.fileOf("b2");
        Path b3 = composition.fileOf("b3");
        assertThat(List.of(b1, b2, b3)).allSatisfy(p -> assertThat(Files.exists(p)).isTrue());

        composition.removeNote(now);

        assertThat(Files.exists(b1)).isFalse();
        assertThat(Files.exists(b2)).isFalse();
        assertThat(Files.exists(root.resolve(".notes").resolve(now + ".json"))).isFalse();

        assertThat(store.note(now)).isNull();
        assertThat(composition.blocks(now)).isEmpty();
        assertThat(composition.block("b1")).isNull();

        assertThat(store.note(other)).isNotNull();
        assertThat(Files.exists(b3)).isTrue();
        assertThat(composition.noteMarkdown(other)).isEqualTo("别的内容");
    }

    @Test
    @DisplayName("正文、几何与插件扩展区都能从磁盘原样读回")
    void roundTripsThroughDisk() {
        store.writeNoteMeta(NoteMeta.fresh("n1", "往返", "note", null, "src", "ok", "grp-default", 10L)
                .withTags(List.of("标签一"))
                .withAliases(List.of("别名一")));
        composition.putBlock("n1", new BlockMeta(BlockMeta.SCHEMA, "b1", "卡片标题",
                12.5, 340.25, 500, 200, 3, "#ff0000", "doing", true, Map.of(), 10L, 10L),
                "正文内容");
        composition.putExtension("b1", "memory-curve", Map.of("state", "review", "reps", 2));

        NoteMeta note = store.note("n1");
        assertThat(note.title()).isEqualTo("往返");
        assertThat(note.tags()).containsExactly("标签一");
        assertThat(note.aliases()).containsExactly("别名一");
        assertThat(note.notebookId()).isEqualTo("grp-default");

        BlockMeta block = composition.block("b1");
        assertThat(block.title()).isEqualTo("卡片标题");
        assertThat(block.x()).isEqualTo(12.5);
        assertThat(block.y()).isEqualTo(340.25);
        assertThat(block.w()).isEqualTo(500);
        assertThat(block.z()).isEqualTo(3);
        assertThat(block.color()).isEqualTo("#ff0000");
        assertThat(block.status()).isEqualTo("doing");
        assertThat(block.collapsed()).isTrue();
        assertThat(composition.content("b1")).isEqualTo("正文内容");

        assertThat(composition.extension("b1", "memory-curve"))
                .containsEntry("state", "review")
                .containsEntry("reps", 2);
    }

    @Test
    @DisplayName("两张同名卡片落在两个文件里，不会互相覆盖")
    void disambiguatesSameSlug() {
        store.writeNoteMeta(NoteMeta.fresh("n1", "同名测试", "note", null, null, null, null, 1L));
        composition.putBlock("n1", BlockMeta.fresh("bbbbbbbb-1111", 0, 0, "", null, null, 1L), "第一张");
        composition.putBlock("n1", BlockMeta.fresh("bbbbbbbb-2222", 0, 300, "", null, null, 2L), "第二张");

        Path first = composition.fileOf("bbbbbbbb-1111");
        Path second = composition.fileOf("bbbbbbbb-2222");
        assertThat(first).isNotEqualTo(second);
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.exists(second)).isTrue();
        assertThat(composition.blocks("n1")).hasSize(2);
    }

    @Test
    @DisplayName("笔记 meta 丢失时卡片仍在磁盘上，会被认领成一篇独立笔记")
    void orphanCardIsAdoptedAsItsOwnNote() throws IOException {
        writeCard("only", "card-x", "note-lost", 0, "孤立卡片的内容");
        Files.deleteIfExists(root.resolve(".notes").resolve("note-lost.json"));

        assertThat(store.note("note-lost")).isNull();
        assertThat(store.scanNotes()).isEmpty();

        // 对账做的事：发现清单里没有这个文件 → 认领成一篇新笔记
        int applied = composition.claimUnmanaged();

        assertThat(applied).isEqualTo(1);
        assertThat(store.scanNotes()).hasSize(1);
        assertThat(composition.allBlocks()).hasSize(1);
        assertThat(composition.content("card-x")).isEqualTo("孤立卡片的内容");
        assertThat(Files.exists(root.resolve(".notes")
                .resolve(store.scanNotes().get(0).id() + ".json"))).isTrue();
    }

    @Test
    @DisplayName("台账文件不会被当成笔记读：没有 id 的 json 一律跳过")
    void ledgerFilesAreNotNotes() throws IOException {
        Path metaDir = root.resolve(".notes");
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("notebooks.json"),
                "{\"v\":1,\"notebooks\":[{\"v\":1,\"id\":\"grp-default\",\"type\":\"notebook\","
                        + "\"title\":\"收集箱\",\"parentId\":null,\"icon\":null,\"sortOrder\":0,"
                        + "\"createdAt\":1,\"updatedAt\":1}]}");
        Files.writeString(metaDir.resolve("tags.json"), "{\"设计\":\"#ff0000\"}");
        Files.writeString(metaDir.resolve("settings.json"), "{\"device_id\":\"x\"}");
        Files.writeString(metaDir.resolve("snapshots.json"), "{\"v\":1,\"entries\":[]}");
        Files.writeString(metaDir.resolve("random.json"), "{\"title\":\"手写但没有 id\"}");

        assertThat(store.scanNotes()).isEmpty();
    }

    @Test
    @DisplayName("遗留的 sidecar 不会被清理，也不会污染索引")
    void legacySidecarIsIgnoredNotSwept() throws IOException {
        Path md = store.writeBlock("残留测试", new BlockMeta(BlockMeta.SCHEMA, "bbbbbbbb-2222", "",
                0, 0, BlockMeta.DEFAULT_W, BlockMeta.DEFAULT_H, 0, null, null, false,
                Map.of(), 1L, 1L), "残留卡片正文", null);
        Path sidecar = NoteFilePaths.sidecarFor(md);
        Files.writeString(sidecar, "{\"v\":1,\"id\":\"bbbbbbbb-2222\",\"noteId\":\"wrong-note\"}",
                StandardCharsets.UTF_8);

        maintenance.run();

        assertThat(Files.exists(sidecar)).isTrue();
        assertThat(store.scanNotes()).hasSize(1);
        assertThat(store.scanNotes().get(0).cards()).hasSize(1);
        assertThat(store.scanNotes().get(0).cards().get(0).id()).isEqualTo("bbbbbbbb-2222");
    }

    @Test
    @DisplayName("孤立的 sidecar（没有同名 md）留在原地，且不会被认领成笔记")
    void orphanSidecarIsNotClaimed() throws IOException {
        Path sidecar = root.resolve("早就删掉的卡片-deadbeef.meta.json");
        Files.writeString(sidecar, "{\"v\":1,\"id\":\"deadbeef-1111\",\"noteId\":\"gone\"}",
                StandardCharsets.UTF_8);

        maintenance.run();

        assertThat(Files.exists(sidecar)).isTrue();
        assertThat(store.scanNotes()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void writeNoteMeta(NoteMeta note) {
        store.writeNoteMeta(note);
    }

    /** 造一张"磁盘上已有"的卡片：写 md（含头部注释）并把它挂到所属笔记的清单上。 */
    private void writeCard(String slug, String blockId, String noteId, int slot, String content)
            throws IOException {
        Path md = root.resolve(slug + "-" + NoteFilePaths.shortId(blockId) + ".md");
        BlockMeta block = new BlockMeta(BlockMeta.SCHEMA, blockId, "",
                0, slot * 300.0, BlockMeta.DEFAULT_W, BlockMeta.DEFAULT_H, slot,
                null, null, false, Map.of(), 1L, 1L);
        store.writeBlock("测试笔记", block, content, md);
        NoteMeta note = store.readMeta(store.paths().noteMeta(noteId), NoteMeta.class);
        if (note != null) {
            List<CardRef> cards = new ArrayList<>(note.cards());
            cards.add(Math.max(0, Math.min(slot, cards.size())),
                    CardRef.of(blockId, store.paths().relative(md)));
            store.writeNoteMeta(note.withSchema(NoteMeta.SCHEMA).withCards(cards));
        }
    }
}
