package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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


    private NoteRepository store;
    private CardRepository cardRepository;

    @BeforeEach
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        cardRepository = new CardRepository(store);
        com.thisnode.ext.notes.util.JsonFileUtils.write(store.noteFilePaths().noteMetaFilePath(NOTE),
                NoteMeta.newNoteMeta(NOTE, "现读测试", "note", null));
    }

    private void putCard(String id, String body) {
        cardRepository.putCard(NOTE, CardMeta.newCardMeta(id, 0, 0, "", null, null, 1L), body);
    }

    @Test
    @DisplayName("磁盘上直接改卡片正文后，content 立刻读到新内容（没有缓存）")
    void contentIsAlwaysReadFromDisk() throws IOException {
        putCard("card-1", "旧内容");
        assertThat(store.content("card-1")).isEqualTo("旧内容");

        Path file = store.fileOf("card-1");
        Files.writeString(file, CardFile.compose(store.card("card-1"), "磁盘上新写的内容"),
                StandardCharsets.UTF_8);

        assertThat(store.content("card-1")).isEqualTo("磁盘上新写的内容");
    }

    @Test
    @DisplayName("笔记 markdown 同样是现读拼接")
    void noteMarkdownIsAlwaysReadFromDisk() throws IOException {
        putCard("card-1", "第一段");
        putCard("card-2", "第二段");

        Path file = store.fileOf("card-2");
        Files.writeString(file, CardFile.compose(store.card("card-2"), "第二段（改过）"),
                StandardCharsets.UTF_8);

        assertThat(store.noteMarkdown(NOTE)).contains("第一段").contains("第二段（改过）");
    }
}
