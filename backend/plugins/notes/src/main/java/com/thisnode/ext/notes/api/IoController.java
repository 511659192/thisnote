package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.service.NoteSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 笔记目录的维护端点（F-IO-01..04）。
 *
 * <p>文件成为权威之后，这里的"镜像"动作换了含义：不再有"把数据库里的笔记写成文件"，只剩下
 * "磁盘变了，让索引跟上"。**端点名与返回字段刻意保持不变** —— 界面与脚本还在用，
 * 改名字的收益远小于把它们的调用面全找出来的成本。
 */
@RestController
@RequestMapping("/api/io")
@RequiredArgsConstructor
public class IoController {

    private final NoteService noteService;
    private final NoteSyncService noteSync;
    private final NotesConfig notesConfig;

    /** 原「重建镜像」：现在是「重扫笔记目录」——磁盘是权威，索引向它对齐。 */
    @PostMapping("/mirror/rewrite")
    public Map<String, Object> rewriteMirror() {
        noteSync.rescanNow();
        noteService.log("system", null, "note-rescan", Map.of());
        return Map.of("rewritten", noteSync.status().get("notes"),
                "root", notesConfig.markdownRoot().toString());
    }

    @GetMapping("/mirror/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", notesConfig.markdownRoot().toString());
        out.put("attachmentRoot", notesConfig.attachmentRoot().toString());
        out.put("orphanFiles", 0);
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
