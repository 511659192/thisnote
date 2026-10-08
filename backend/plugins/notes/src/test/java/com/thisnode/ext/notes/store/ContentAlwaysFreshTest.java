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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正文**不留在内存**：每次读都现读卡片文件，所以直接在磁盘上改文件，接口立刻看到新内容。
 *
 * <p>这是"按需 scan、成员变量全部不要"的第一阶段：正文缓存已经删掉。
 */
class ContentAlwaysFreshTest {

    @TempDir
    Path root;

    private static final String NOTE = "note-fresh";

    private NoteCompositionService composition;

    @BeforeEach
    void setUp() {
        NotesConfig config = Mockito.mock(NotesConfig.class);
        Mockito.when(config.markdownRoot()).thenReturn(root);
        NoteStore store = new NoteStore(config);
        composition = new NoteCompositionService(store);
        store.writeNoteMeta(NoteMeta.fresh(NOTE, "现读测试", "note", null, null, null, null, 1L));
    }

    private void putCard(String id, String body) {
        composition.putBlock(NOTE, BlockMeta.fresh(id, 0, 0, "", null, null, 1L), body);
    }

    @Test
    @DisplayName("磁盘上直接改卡片正文后，content 立刻读到新内容（没有缓存）")
    void contentIsAlwaysReadFromDisk() throws IOException {
        putCard("card-1", "旧内容");
        assertThat(composition.content("card-1")).isEqualTo("旧内容");

        Path file = composition.fileOf("card-1");
        Files.writeString(file, CardFile.compose(composition.block("card-1"), "磁盘上新写的内容"),
                StandardCharsets.UTF_8);

        assertThat(composition.content("card-1")).isEqualTo("磁盘上新写的内容");
    }

    @Test
    @DisplayName("笔记 markdown 同样是现读拼接")
    void noteMarkdownIsAlwaysReadFromDisk() throws IOException {
        putCard("card-1", "第一段");
        putCard("card-2", "第二段");

        Path file = composition.fileOf("card-2");
        Files.writeString(file, CardFile.compose(composition.block("card-2"), "第二段（改过）"),
                StandardCharsets.UTF_8);

        assertThat(composition.noteMarkdown(NOTE)).contains("第一段").contains("第二段（改过）");
    }
}
