package com.thisnode.ext.notes.store;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.util.Markdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 笔记目录的文件读写（唯一入口）：笔记 meta 与卡片文件都从这里进出。
 *
 * <p>这里**不持有任何内存状态**：每次调用直接扫目录 / 读那一个文件 —— 文件才是权威，任何时刻都
 * 可能被外部改动，所以读到的一定是盘上最新内容，"盘上现在是什么"随时可以重扫。
 *
 * <p>所有写入走"临时文件 + 原子替换"：卡片保存时程序可能被关掉，半截文件比没有文件更糟。
 */
@Component
public class NoteStore {

    private static final Logger log = LoggerFactory.getLogger(NoteStore.class);

    private final NoteFilePaths noteFilePaths;

    public NoteStore(NotesConfig config) {
        this.noteFilePaths = new NoteFilePaths(config.markdownRoot());
    }

    public NoteFilePaths paths() {
        return noteFilePaths;
    }

    /**
     * 读一张卡片文件的结果。
     *
     * @param meta    {@code null} 表示这个 md 没有 meta 注释（需要认领）
     * @param content 正文（已剥掉头部 meta 注释）
     */
    public record CardData(BlockMeta meta, String content) {
    }

    /** 读一张卡片：注释里的 meta + 正文。懒加载与迁移都走它。 */
    public CardData readCard(Path file) {
        String raw = readText(file);
        if (raw == null) {
            return null;
        }
        return new CardData(CardFile.metaOf(raw), CardFile.bodyOf(raw));
    }

    /** 卡片文件在磁盘上的路径（{@code CardRef.file} 是相对路径）。 */
    public Path cardPath(CardRef ref) {
        return ref == null ? null : noteFilePaths.root().resolve(ref.file());
    }

    /** 读一张卡（元数据 + 正文）；ref 为空或文件不在时返回 {@code null}。 */
    public CardData readCard(CardRef ref) {
        Path file = cardPath(ref);
        return file == null ? null : readCard(file);
    }

    /** 只取卡片正文（文件不在或读不出来时给空串）。 */
    public String cardContent(CardRef ref) {
        CardData data = readCard(ref);
        return data == null ? "" : data.content();
    }

    /** 一次目录遍历的结果：卡片文件（相对路径 → mtime）。 */
    public record FileListing(Map<String, Long> cards) {
    }

    /**
     * 遍历卡片目录一次，拿到卡片文件清单。
     *
     * <p>合并的理由是实测出来的：10000 个文件的一次 {@code Files.walk} 在这台机器上约 0.8s，
     * 分开扫三次就是启动时白花 1.6s。文件系统遍历是 O(卡片数) 的操作，**能少一次就少一次**。
     */
    public FileListing listFiles() {
        Map<String, Long> cards = new LinkedHashMap<>();
        Path cardsDir = noteFilePaths.cardsDir();
        if (!Files.isDirectory(cardsDir)) {
            return new FileListing(cards);
        }
        try (Stream<Path> stream = Files.walk(cardsDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!noteFilePaths.isContentFile(file)) {
                    continue;
                }
                try {
                    cards.put(noteFilePaths.relative(file), Files.getLastModifiedTime(file).toMillis());
                } catch (IOException e) {
                    log.debug("无法 stat {}：{}", noteFilePaths.relative(file), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("卡片目录无法遍历：{}（{}）", noteFilePaths.relative(cardsDir), e.toString());
        }
        return new FileListing(cards);
    }

    /** 只取卡片文件清单（变更检测用）。 */
    public Map<String, Long> listCardFiles() {
        return listFiles().cards();
    }

    /**
     * 读全部笔记 meta（{@code .notes/meta/<noteId>.json}）。
     *
     * <p>meta 目录里只有笔记 meta，不需要台账黑名单；单个坏文件只跳过并记日志：meta 是用户可
     * 手改的，一个写坏的字符不该让整库打不开。
     */
    public List<NoteMeta> scanNotes() {
        List<NoteMeta> notes = new ArrayList<>();
        Path metaDir = noteFilePaths.noteMetaDir();
        if (!Files.isDirectory(metaDir)) {
            return notes;
        }
        try (Stream<Path> stream = Files.list(metaDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                NoteMeta meta = readMeta(file, NoteMeta.class);
                if (meta == null) {
                    continue;
                }
                if (meta.id() == null || meta.id().isBlank()) {
                    log.warn("{} 不是笔记元数据（缺 id），已跳过", noteFilePaths.relative(file));
                    continue;
                }
                notes.add(meta);
            }
        } catch (IOException e) {
            log.warn("笔记元数据目录无法读取：{}（{}）", noteFilePaths.relative(metaDir), e.toString());
        }
        return notes;
    }

    /** 只读一篇笔记的 meta（文件不在或读不出来给 {@code null}）—— 不会为了它扫全库。 */
    public NoteMeta note(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return null;
        }
        Path file = noteFilePaths.noteMetaDir().resolve(noteId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return readMeta(file, NoteMeta.class);
    }

    /**
     * 标题解析表（{@code 规范化标题 → 笔记 id}）：**每次现建**，不驻留。
     *
     * <p>重名时先到先得；回收站里的笔记不参与解析。数据库时代的唯一索引等价物。
     */
    public Map<String, String> titleIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        List<NoteMeta> all = scanNotes();
        // 先登记标题，再登记别名：同名时标题赢（别名只是"另一个入口"）。
        for (NoteMeta meta : all) {
            if (meta.deleted() || meta.title() == null || meta.title().isBlank()) {
                continue;
            }
            index.putIfAbsent(Markdown.normalizeTitle(meta.title()), meta.id());
        }
        for (NoteMeta meta : all) {
            if (meta.deleted()) {
                continue;
            }
            for (String alias : meta.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    index.putIfAbsent(Markdown.normalizeTitle(alias), meta.id());
                }
            }
        }
        return index;
    }

    /** 解析 {@code [[标题]]}：现建解析表后查一次，查不到给 {@code null}。 */
    public String resolveTitle(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        return titleIndex().get(Markdown.normalizeTitle(target));
    }

    // ------------------------------------------------------------------ write

    public void writeNoteMeta(NoteMeta note) {
        writeJson(noteFilePaths.noteMeta(note.id()), note);
    }

    /**
     * 写一张卡片：meta 注释 + 正文，都在这一个 md 文件里。
     *
     * <p>文件名 = {@code <笔记名>-<卡片描述>-<随机串>.md}（见 {@link NoteFilePaths#blockFilePrefix}）。
     * {@code knownFile} 非空表示这张卡已经在盘上了（只改内容）——**沿用原路径**，绝不因为标题变了
     * 就改名：文件名只是给人看的，身份在 meta 的 id 里。
     */
    public Path writeBlock(String noteTitle, BlockMeta block, String contentMd, Path knownFile) {
        Path file = knownFile != null ? knownFile
                : resolveContentPath(noteTitle, NoteFilePaths.blockSlug(block.title(), contentMd),
                        block.id());
        writeText(file, CardFile.compose(block, contentMd));
        return file;
    }

    /** 只改元数据（拖动、改宽度、插件扩展区）：重写头部注释，正文原样保留。 */
    public void writeBlockMeta(BlockMeta block, Path contentFile) {
        String current = readText(contentFile);
        if (current == null) {
            writeText(contentFile, CardFile.compose(block, ""));
            return;
        }
        writeText(contentFile, CardFile.compose(block, CardFile.bodyOf(current)));
    }

    /** 删一张卡片：md 为主，顺手清掉旧版本留下的 sidecar（如果还有）。 */
    public void deleteBlockFiles(Path contentFile) {
        deleteQuietly(contentFile);
        removeLegacySidecar(contentFile);
    }

    /**
     * 清掉旧版本留下的 sidecar（{@code .meta.json}）。
     *
     * <p>meta 现在写在 md 头部注释里、且注释是唯一权威 —— 留着 sidecar 就是两份 meta 并存，
     * 日后总有人（脚本、旧版本、手工）把它当真。所以读到就删，而不是留着等用户清理。
     */
    public void removeLegacySidecar(Path contentFile) {
        deleteQuietly(NoteFilePaths.sidecarFor(contentFile));
    }

    public void deleteNoteMeta(String noteId) {
        deleteQuietly(noteFilePaths.noteMeta(noteId));
    }

    /** 首次使用的目录要建出来，否则扫描与后面的写入都会落空。 */
    public void ensureDirs() {
        try {
            for (Path dir : new Path[]{
                    noteFilePaths.cardsDir(), noteFilePaths.noteMetaDir(),
                    noteFilePaths.ledgersDir(), noteFilePaths.logsDir()
            }) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建笔记目录结构 " + noteFilePaths.root(), e);
        }
    }

    /**
     * 决定一张新卡片落在哪个文件：{@code <slug>-<shortId>.md}。同名文件已存在时先看它是不是同一张卡
     * （重扫后重写的情况），不是就把短 id 逐段加长，实在不行用完整 id 兜底。
     */
    private Path resolveContentPath(String noteTitle, String cardSlug, String blockId) {
        String noteSlug = NoteFilePaths.blockSlug(noteTitle, null);
        String prefix = NoteFilePaths.blockFilePrefix(noteSlug, cardSlug);
        Path candidate = noteFilePaths.blockContent(noteSlug, cardSlug, blockId);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        // 笔记名、卡片描述都一样（同名同描述的两张卡）：把随机串逐段加长，直到不撞车。
        String hex = blockId.replace("-", "");
        for (int len = 12; len < hex.length(); len += 4) {
            Path longer = noteFilePaths.cardsDir().resolve(prefix + "-" + hex.substring(0, len)
                    + NoteFilePaths.MD_SUFFIX);
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return noteFilePaths.cardsDir().resolve(prefix + "-" + blockId + NoteFilePaths.MD_SUFFIX);
    }

    // ------------------------------------------------------------------- text

    public String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("文件无法读取：{}（{}）", noteFilePaths.relative(file), e.toString());
            return null;
        }
    }

    public void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            move(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败 " + file, e);
        }
    }

    /** 追加一行（操作日志用）：日志丢了不比丢一次写入更严重，所以失败只记日志不抛。 */
    public void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("无法追加 {}（{}）", noteFilePaths.relative(file), e.toString());
        }
    }

    public void writeJson(Path file, Object value) {
        writeText(file, MetaJson.toJson(value));
    }

    public <T> T readMeta(Path file, Class<T> type) {
        try {
            return MetaJson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(file)) {
                log.warn("元数据无法解析，已跳过：{}（{}）", noteFilePaths.relative(file), e.toString());
            }
            return null;
        }
    }

    public void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除失败：{}（{}）", noteFilePaths.relative(file), e.toString());
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
