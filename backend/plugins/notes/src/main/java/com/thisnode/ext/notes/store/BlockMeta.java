package com.thisnode.ext.notes.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一张卡片的元数据：**它自己的**身份与位置。
 *
 * <p>注意这里没有 {@code noteId}：归属方向是「**笔记持有卡片清单**」（见
 * {@link NoteMeta#cards}）。这样加载只需要读笔记的 meta，卡片文件可以懒加载 —— 上万张卡片时
 * 启动不再是"读一万个文件"。卡片自己不需要知道它属于谁，因此把一张 md 拷进目录就是一张"无主"
 * 卡片，由定时器认领成独立笔记。
 *
 * <p>{@code ext} 是插件扩展区：按插件 id 分区存放插件自己的卡片级数据（记忆曲线的排期就落在这里），
 * 核心不认识任何具体插件的键。卡片文件被删除时扩展区一起消失，插件的从属数据不会留下孤儿。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockMeta(
        int v,
        String id,
        String title,
        double x,
        double y,
        double w,
        double h,
        int z,
        String color,
        String status,
        boolean collapsed,
        Map<String, Object> ext,
        long createdAt,
        long updatedAt) {

    public static final int SCHEMA = 2;

    /** 卡片几何默认值，与旧 note_blocks 表的默认值保持一致。 */
    public static final double DEFAULT_W = 720;
    public static final double DEFAULT_H = 260;
    public static final double MIN_W = 240;
    public static final double MIN_H = 88;

    public BlockMeta {
        v = v <= 0 ? SCHEMA : v;
        title = title == null ? "" : title;
        // 非正数当作"没给尺寸"，用默认；给了正数就只夹到下限，不改用户的意图。
        w = w <= 0 ? DEFAULT_W : Math.max(MIN_W, w);
        h = h <= 0 ? DEFAULT_H : Math.max(MIN_H, h);
        ext = ext == null ? Map.of() : Map.copyOf(ext);
    }

    public static BlockMeta fresh(String id, double x, double y, String title,
                                  String color, String status, long now) {
        return new BlockMeta(SCHEMA, id, title, x, y, DEFAULT_W, DEFAULT_H, 0,
                color, status, false, Map.of(), now, now);
    }

    public BlockMeta touched(long now) {
        return new BlockMeta(v, id, title, x, y, w, h, z, color, status, collapsed,
                ext, createdAt, now);
    }

    public BlockMeta withGeometry(Double nx, Double ny, Double nw, Double nh, Integer nz) {
        return new BlockMeta(v, id, title,
                nx == null ? x : nx, ny == null ? y : ny,
                nw == null ? w : Math.max(MIN_W, nw), nh == null ? h : Math.max(MIN_H, nh),
                nz == null ? z : nz, color, status, collapsed, ext, createdAt, updatedAt);
    }

    public BlockMeta withTitle(String next) {
        return new BlockMeta(v, id, next == null ? "" : next, x, y, w, h, z,
                color, status, collapsed, ext, createdAt, updatedAt);
    }

    public BlockMeta withAppearance(String ncolor, String nstatus, Boolean ncollapsed) {
        return new BlockMeta(v, id, title, x, y, w, h, z,
                ncolor == null ? color : ncolor,
                nstatus == null ? status : nstatus,
                ncollapsed == null ? collapsed : ncollapsed,
                ext, createdAt, updatedAt);
    }

    /** 写入插件扩展区的一个分区；插件数据只走这条路，核心不解释它的内容。 */
    public BlockMeta withExtension(String pluginId, Map<String, Object> value) {
        Map<String, Object> next = new LinkedHashMap<>(ext);
        if (value == null) {
            next.remove(pluginId);
        } else {
            next.put(pluginId, value);
        }
        return new BlockMeta(v, id, title, x, y, w, h, z, color, status, collapsed,
                next, createdAt, updatedAt);
    }
}
