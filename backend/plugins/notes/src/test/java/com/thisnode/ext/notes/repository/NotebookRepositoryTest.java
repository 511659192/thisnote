package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.domain.NoteFilePaths;
import com.thisnode.ext.notes.domain.CardMeta;
import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分组树的基本规则：全量替换的移动语义（parentId=null 即顶层）、环与存在性校验、
 * 默认分组不可挂父级、删除分组时其中的笔记回默认分组。全部不启 Spring，临时目录上跑。
 */
class NotebookRepositoryTest {

    @TempDir
    Path root;

    private NoteRepository store;
    private NotebookRepository notebooks;

    @BeforeEach
    void setUp() {
        store = new NoteRepository(new NoteFilePaths(root));
        notebooks = new NotebookRepository(store);
    }

    @Test
    @DisplayName("移到顶层：parentId 传 null 就离开父级（全量替换，不再有 null=保持不变）")
    void moveToTopClearsParent() {
        String parent = notebooks.create("父分组", null, null, "notebook");
        String child = notebooks.create("子分组", parent, null, "notebook");
        assertThat(notebooks.find(child).parentId()).isEqualTo(parent);

        notebooks.update(child, "子分组", null, null, null);

        assertThat(notebooks.find(child).parentId()).isNull();
        assertThat(notebooks.find(child).title()).isEqualTo("子分组");

        // 再挂回父级 → 又有父级
        notebooks.update(child, "子分组", parent, null, null);
        assertThat(notebooks.find(child).parentId()).isEqualTo(parent);
    }

    @Test
    @DisplayName("移动/重命名校验：不能移到自己或后代下面，父级必须存在，默认分组不能挂父级")
    void moveValidations() {
        String a = notebooks.create("A", null, null, "notebook");
        String b = notebooks.create("B", a, null, "notebook");

        assertThatThrownBy(() -> notebooks.update(a, "A", a, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // b 在 a 的子树里：把 a 移到 b 下会成环
        assertThatThrownBy(() -> notebooks.update(a, "A", b, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notebooks.create("孤儿", "grp-missing", null, "notebook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notebooks.update(NotebookRepository.DEFAULT_GROUP_ID,
                NotebookRepository.DEFAULT_GROUP_TITLE, a, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("创建子分组：parentId 落在台账上；删除分组时笔记回默认分组、子分组上提一级")
    void subgroupAndDeleteFallback() {
        String parent = notebooks.create("父分组", null, null, "notebook");
        String sub = notebooks.create("子分组", parent, null, "notebook");
        assertThat(notebooks.find(sub).parentId()).isEqualTo(parent);

        String noteId = store.insert("带归属的笔记", "note");
        new CardRepository(store).putCard(noteId, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null,
                System.currentTimeMillis()), "");
        notebooks.addNodeToNotebook(noteId, parent);

        notebooks.delete(parent);

        // 被删的分组消失；子分组不连坐，上提到顶层；笔记回默认分组
        assertThat(notebooks.find(parent)).isNull();
        assertThat(notebooks.find(sub).parentId()).isNull();
        assertThat(store.getOneNoteMeta(noteId).getNotebookId()).isEqualTo(NotebookRepository.DEFAULT_GROUP_ID);
    }
}
