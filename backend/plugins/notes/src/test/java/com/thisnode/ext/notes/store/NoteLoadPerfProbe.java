package com.thisnode.ext.notes.store;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.NoteRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 临时压测探针（跑完即删）：量一下"全量扫描 + 解析头部注释 + 建索引"在几千到上万张卡片时的代价，
 * 以及一次重扫与一次笔记页面查询的耗时。
 */
class NoteLoadPerfProbe {

    private static final int CARDS_PER_NOTE = 50;

    @Test
    @Disabled("临时压测：改数据模型前后手动开启对比，不进常规 mvn test")
    @DisplayName("压测：卡片数 vs 加载 / 重扫 / 页面查询耗时")
    void probe() throws IOException {
        for (int cards : new int[]{1000, 5000, 10000}) {
            Path caseRoot = Files.createTempDirectory("note-perf-" + cards + "-");
            NotesConfig config = Mockito.mock(NotesConfig.class);
            Mockito.when(config.markdownRoot()).thenReturn(caseRoot);
            NoteRepository store = new NoteRepository(config);
            seed(store, caseRoot, cards);

                StartupMaintenance maintenance = new StartupMaintenance(store);

            long t0 = System.nanoTime();
            maintenance.run();
            long loadMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            store.getAll();
            long reloadMs = (System.nanoTime() - t1) / 1_000_000;

            String noteId = store.getAll().get(0).id();
            long t2 = System.nanoTime();
            int blocks = store.cards(noteId).size();
            int mdLen = store.noteMarkdown(noteId).length();
            long pageMs = (System.nanoTime() - t2) / 1_000_000;

            long t3 = System.nanoTime();
            int all = store.allCards().size();
            long scanMs = (System.nanoTime() - t3) / 1_000_000;

            Runtime rt = Runtime.getRuntime();
            System.gc();
            long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

            System.out.println("PERF cards=" + all + " notes=" + store.getAll().size()
                    + " | load=" + loadMs + "ms reload=" + reloadMs + "ms"
                    + " pageQuery=" + pageMs + "ms (blocks=" + blocks + ", mdLen=" + mdLen + ")"
                    + " allBlocks=" + scanMs + "ms heap~" + heapMb + "MB");

            deleteRecursively(caseRoot);
        }
    }

    private void seed(NoteRepository store, Path root, int cards) throws IOException {
        int notes = Math.max(1, cards / CARDS_PER_NOTE);
        long now = System.currentTimeMillis();
        String tag = root.getFileName().toString();
        for (int n = 0; n < notes; n++) {
            String noteId = "note-" + tag + "-" + n;
            java.util.List<CardRef> refs = new java.util.ArrayList<>();
            for (int c = 0; c < CARDS_PER_NOTE; c++) {
                String blockId = java.util.UUID.randomUUID().toString();
                String slug = "卡片-" + n + "-" + c;
                Path md = root.resolve(slug + "-" + NoteFilePaths.shortId(blockId) + ".md");
                Files.writeString(md, CardFile.compose(new CardMeta(CardMeta.SCHEMA, blockId,
                        "", 0, c * 300.0, 720, 260, c, null, null, false, Map.of(),
                        now, now), "正文 " + c + "\n" + "x".repeat(200)), StandardCharsets.UTF_8);
                refs.add(CardRef.of(blockId, root.relativize(md).toString().replace('\\', '/')));
            }
            store.writeNoteMeta(NoteMeta.fresh(noteId, "笔记 " + n, "note", null, null, null,
                    "grp-default", now).withCards(refs));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
