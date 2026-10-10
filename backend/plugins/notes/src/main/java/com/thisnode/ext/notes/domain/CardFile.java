package com.thisnode.ext.notes.domain;

import com.alibaba.fastjson2.JSON;

/**
 * 卡片文件的格式：一行 meta 注释 + 正文。
 *
 * <pre>
 *   &lt;!-- thisnode:meta {"v":2,"id":"…","x":0,"y":0,"w":720,"h":260,"z":0,…} --&gt;
 *   这里是卡片正文。
 * </pre>
 *
 * <p>元数据写在 **markdown 注释**里，而不是 frontmatter，也不是同名的 sidecar 文件：
 * <ul>
 *   <li>注释在渲染时不可见，也不会被 Obsidian 当成"属性/标签"处理，所以卡片正文保持干净；</li>
 *   <li>注释跟着正文走，一个 md 文件就是一张自足的卡片 —— 复制、移动、丢进目录都能被识别，
 *       不需要旁边那个 {@code .meta.json} 一起跟着；</li>
 *   <li>只有一个真相源：不会出现"注释说在 A 笔记、sidecar 说在 B 笔记"这种分歧。</li>
 * </ul>
 *
 * <p>只认**文件头部第一行**的注释。这样用户手动挪动正文、在末尾追加内容都不会破坏识别，
 * 也不需要在正文里到处找元数据。
 */
public final class CardFile {

    public static final String META_PREFIX = "<!-- thisnode:meta ";
    public static final String META_SUFFIX = " -->";

    private CardFile() {
    }

    /**
     * 磁盘上的卡片内容 = meta 注释 + 换行 + 正文。
     */
    public static String compose(CardMeta meta,
                                 String contentMd) {
        String json = JSON.toJSONString(meta).replace("\r", " ").replace("\n", " ");
        return META_PREFIX + json + META_SUFFIX + "\n" + (contentMd == null ? "" : contentMd);
    }

    /**
     * 取头部注释里的 meta；没有注释、或注释坏了返回 {@code null}（调用方按"无元数据"处理）。
     */
    public static CardMeta metaOf(String fileContent) {
        String firstLine = firstLineOf(fileContent);
        if (!firstLine.startsWith(META_PREFIX) || !firstLine.endsWith(META_SUFFIX) || firstLine.length() < META_PREFIX.length() + META_SUFFIX.length()) {
            return null;
        }
        String json = firstLine.substring(META_PREFIX.length(), firstLine.length() - META_SUFFIX.length()).strip();
        try {
            return JSON.parseObject(json, CardMeta.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 正文 = 文件内容去掉头部注释行（没有注释就是原样）。
     */
    public static String bodyOf(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        if (!firstLineOf(fileContent).startsWith(META_PREFIX)) {
            return fileContent;
        }
        return newline < 0 ? "" : fileContent.substring(newline + 1);
    }

    private static String firstLineOf(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        String first = newline < 0 ? fileContent : fileContent.substring(0, newline);
        return first.strip();
    }
}
