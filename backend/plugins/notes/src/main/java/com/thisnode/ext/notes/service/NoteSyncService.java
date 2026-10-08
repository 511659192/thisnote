package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.OpsRepository;
import com.thisnode.ext.notes.store.NoteStore;
import com.thisnode.ext.notes.store.NoteCompositionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记目录的外部改动检测。
 *
 * <p>文件是权威，所以这里要回答的问题只有一个：**磁盘上多了什么？**
 * 做法是现算"已知文件集合"（来自各篇笔记 meta 的卡片清单），与磁盘上的 md 比对，只认领不在
 * 清单里的那些（{@link NoteCompositionService#claimUnmanaged()}）—— 只读那几个新文件，
 * 没有 meta 注释就当场补上并让它成为一篇独立笔记。
 *
 * <p>与旧实现的关键差别：① 不再"指纹变了就整库重扫"（10000 张卡片时是秒级，而用户存一次盘
 * 每几秒就可能发生一次）；② **不再维护 mtime 基线与缓存失效** —— 正文与 meta 现在都是按需现读，
 * 没有缓存需要失效，"改过的文件"这件事自然就不需要检测了。
 */
@Service
@RequiredArgsConstructor
public class NoteSyncService {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncService.class);

    private final NoteStore noteStore;
    private final NoteCompositionService composition;
    private final NotesConfig notesConfig;
    private final OpsRepository opsRepository;

    /** 来自 application.yml 的默认值；用户改过的持久化取值在 {@link #initEnabled()} 里覆盖。 */
    @Value("${thisnode.note-watch:true}")
    private boolean defaultEnabled;

    private volatile boolean enabled;

    private final List<Map<String, Object>> recentEvents = new ArrayList<>();
    private volatile long lastScanAt;

    /**
     * 开关的取值优先级：持久化设置 → application.yml 默认。
     */
    @PostConstruct
    void initEnabled() {
        String stored = opsRepository.setting("note-watch");
        enabled = stored == null ? defaultEnabled : Boolean.parseBoolean(stored);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /**
     * Poll loop。
     *
     * <p>间隔默认 30 秒而不是 4 秒：一次对账要走一遍目录（O(卡片数)，10000 个文件约 0.8s），
     * 每 4 秒做一次等于常驻 20% 的 IO。目录事件（{@code WatchService}）能做到真正的实时且零轮询
     * 成本，那是下一步的事；在只靠定时器的前提下，30 秒是"够快"与"不浪费"之间的取舍。
     */
    @Value("${thisnode.note-poll-ms:30000}")
    private long pollIntervalMs;

    @Scheduled(initialDelay = 8_000, fixedDelayString = "${thisnode.note-poll-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("note-directory reconcile failed: {}", e.toString());
        }
    }

    /** 一次对账；返回实际应用的变化数。 */
    public int reconcile() {
        lastScanAt = System.currentTimeMillis();
        int applied = composition.claimUnmanaged() + composition.dropMissingCards();
        if (applied > 0) {
            note("scan", "笔记目录", "应用了 " + applied + " 处外部改动");
        } else {
            log.debug("对账完成：无变化（轮询间隔 {}ms）", pollIntervalMs);
        }
        return applied;
    }

    /**
     * 立刻处理目录差异（维护端点的"重建"用它）。
     *
     * <p>现在每次读取都是现扫，**没有"内存索引"可以重载** —— 这个方法只需要认领游离文件；
     * 各篇的列表页缓存由 {@code POST /api/maintenance/reindex} 另行重算。
     *
     * @return 认领的文件数
     */
    public int rescanNow() {
        int applied = composition.claimUnmanaged();
        note("rescan", "笔记目录", "已按请求重新认领游离文件");
        return applied;
    }

    private void note(String kind, String title, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", kind);
        event.put("title", title);
        event.put("message", message);
        event.put("at", System.currentTimeMillis());
        synchronized (recentEvents) {
            recentEvents.add(0, event);
            while (recentEvents.size() > 40) {
                recentEvents.remove(recentEvents.size() - 1);
            }
        }
        log.info("[notes] {} {} — {}", kind, title, message);
    }

    public List<Map<String, Object>> recentEvents() {
        synchronized (recentEvents) {
            return new ArrayList<>(recentEvents);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("root", notesConfig.markdownRoot().toString());
        m.put("notes", noteStore.scanNotes().size());
        m.put("blocks", composition.blockCounts().values().stream().mapToInt(Integer::intValue).sum());
        m.put("lastScanAt", lastScanAt);
        m.put("events", recentEvents());
        return m;
    }
}
