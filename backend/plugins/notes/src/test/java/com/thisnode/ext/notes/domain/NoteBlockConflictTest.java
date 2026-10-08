package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.store.BlockMeta;
import com.thisnode.ext.notes.store.CardFile;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.store.NoteStore;
import com.thisnode.plugin.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保存卡片时的冲突保护：客户端带上「它编辑时所依据的正文」，磁盘上已经不是那一份就必须拒写。
 *
 * <p>为什么基线来自客户端而不是索引缓存：外部编辑后、下一次对账（默认 30s）之前，索引里还是
 * 旧内容 —— 只比缓存会漏判，于是把外部改动覆盖掉。这里因此直接比对**磁盘**，缓存里的旧值不算数。
 *
 * <p>不启 Spring：{@link NoteStore} 只依赖 markdown 根目录，用临时目录直接跑。
 */
class NoteBlockConflictTest {

    @TempDir
    Path root;

    private NoteStore store;
    private NoteCompositionService composition;
    private NoteBlockRepository repository;

    @BeforeEach
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        store = new NoteStore(config);
        composition = TestWiring.composition(store);
        repository = new NoteBlockRepository(TestWiring.composition(store));
    }

    /** 建一篇笔记 + 一张卡，返回卡片 id（正文 = 原始内容）。 */
    private String seedCard() {
        String noteId = "note-1";
        store.writeNoteMeta(NoteMeta.fresh(noteId, "笔记", "note", null, null, null, null, 1L));
        composition.putBlock(noteId, BlockMeta.fresh("card-1", 0, 0, "", null, null, 1L), "原始内容");
        return "card-1";
    }

    /** 绕过索引、直接改磁盘上的卡片文件：这正是外部编辑器（Obsidian / VS Code）在做的事。 */
    private void editOnDisk(String blockId, String body) throws IOException {
        Path file = composition.fileOf(blockId);
        Files.writeString(file, CardFile.compose(composition.block(blockId), body), StandardCharsets.UTF_8);
    }

    private String bodyOnDisk(String blockId) throws IOException {
        return CardFile.bodyOf(Files.readString(composition.fileOf(blockId), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("基线过期（磁盘已被外部改过）→ 拒写，且外部改动原样保留")
    void rejectsStaleBase() throws IOException {
        String blockId = seedCard();
        editOnDisk(blockId, "外部改过的正文");

        assertThatThrownBy(() -> repository.setContent(blockId, "应用里的新内容", "原始内容"))
                .isInstanceOf(ConflictException.class);

        assertThat(bodyOnDisk(blockId)).isEqualTo("外部改过的正文");
    }

    @Test
    @DisplayName("基线就是磁盘当前正文 → 正常写入")
    void acceptsMatchingBase() throws IOException {
        String blockId = seedCard();
        editOnDisk(blockId, "外部改过的正文");

        repository.setContent(blockId, "应用里的新内容", "外部改过的正文");

        assertThat(bodyOnDisk(blockId)).isEqualTo("应用里的新内容");
    }

    @Test
    @DisplayName("不带基线（旧客户端）→ 保持原有行为")
    void keepsLegacyBehaviourWithoutBase() throws IOException {
        String blockId = seedCard();
        editOnDisk(blockId, "外部改过的正文");

        repository.setContent(blockId, "应用里的新内容", null);

        assertThat(bodyOnDisk(blockId)).isEqualTo("应用里的新内容");
    }

    @Test
    @DisplayName("卡片文件已被外部删掉 → 拒写（不把用户删掉的卡片复活）")
    void rejectsWhenFileIsGone() throws IOException {
        String blockId = seedCard();
        Files.delete(composition.fileOf(blockId));

        assertThatThrownBy(() -> repository.setContent(blockId, "应用里的新内容", "原始内容"))
                .isInstanceOf(ConflictException.class);
    }
}
