package com.thisnode.plugin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link com.thisnode.plugin.api.PluginDataRepository} 的实现：**一个 JSON 文件**
 * （默认 {@code <data-root>/plugin-data.json}）。
 *
 * <p>文件形状就是那个 Map：{@code pluginId → entityId → true}，两级，键顺序稳定，
 * 便于手工查看与修改：
 * <pre>
 * {
 *   "favorite":     { "&lt;noteId&gt;": true },
 *   "memory-curve": { "&lt;cardId&gt;": true }
 * }
 * </pre>
 *
 * <p>JSON 用 <b>fastjson2</b>（后端统一的 JSON 库）：{@code JSON.parseObject} / {@code JSON.toJSONString}。
 *
 * <p><b>没有内存缓存</b>：每次调用现读文件（体量小：每插件几条），于是手改文件下一次请求就生效，
 * 也不存在"缓存与文件谁更新"的问题。写入是「读-改-写」，整段一把锁串行，落盘用临时文件 + 原子替换。
 *
 * <p>实现放在平台侧（不是契约模块）：契约不该引 JSON 库；平台本来就有它。
 *
 * <p><b>同名留意：</b>本类与契约接口 {@code com.thisnode.plugin.api.PluginDataRepository} 同名不同包。
 * 平台内部的注入点用本类（包内简单名就指它），插件侧用契约接口；本类实现接口，两者都能收到同一个 bean。
 */
public class PluginDataRepository implements com.thisnode.plugin.api.PluginDataRepository {

    private static final Logger log = LoggerFactory.getLogger(PluginDataRepository.class);

    /**
     * 文件本身的类型：{@code pluginId → 实体 id → 开关}。
     *
     * <p>两级的键都是动态的（插件 id、实体 id），所以数据本身就是一个 Map，
     * 不再包一层领域类型：包了就得请 JSON 库把它拆开（fastjson2 没有「把唯一成员当作整个对象」的注解，
     * {@code @JSONField(unwrapped = true)} 在读路径会静默给空表），而挂在数据上的动作只有
     * 「取表 / 建表 / 关掉并回收空壳」三个，写成下面的私有方法即可。
     */
    private static final TypeReference<Map<String, Map<String, Boolean>>> SHAPE = new TypeReference<>() {
    };

    private final Path file;

    /**
     * 读-改-写必须串行（文件是唯一权威，没有缓存可兜底）。
     */
    private final Object lock = new Object();

    public PluginDataRepository(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /**
     * 写路径取某插件的实体表：没有就建。
     */
    private static Map<String, Boolean> openEntities(Map<String, Map<String, Boolean>> data, String pluginId) {
        return data.computeIfAbsent(pluginId, k -> new LinkedHashMap<>());
    }

    /**
     * 关掉一个实体；关空了连插件那一级一起回收，文件里不留 {@code {}} 残壳。
     *
     * @return 真关掉了才是 {@code true}（本来就没有就不必写盘）
     */
    private static boolean drop(Map<String, Map<String, Boolean>> data, String pluginId, String entityId) {
        Map<String, Boolean> entities = data.get(pluginId);
        if (entities == null || entities.remove(entityId) == null) {
            return false;
        }
        if (entities.isEmpty()) {
            data.remove(pluginId);
        }
        return true;
    }

    // ------------------------------------------------------------------ 读写

    /**
     * 现读整份数据（{@code pluginId → entityId → 开关}）；文件缺失/损坏一律按空库（不抛，也不清空磁盘）。
     *
     * <p>空库是**新建的空表**而不是不可变空表：调用方拿它接着做「读-改-写」，要能往里 put。
     */
    private Map<String, Map<String, Boolean>> read() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("插件状态文件不可读，按空库处理：{}（{}）", file, e.toString());
            return new LinkedHashMap<>();
        }
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Boolean>> data = JSON.parseObject(text, SHAPE);
            return data == null ? new LinkedHashMap<>() : data;
        } catch (RuntimeException e) {
            log.warn("插件状态文件不是合法 JSON，按空库处理：{}（{}）", file, e.toString());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 落盘：pretty JSON + 临时文件原子替换（人类可读、不会被读到半截）。
     */
    private void write(Map<String, Map<String, Boolean>> data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] bytes = JSON.toJSONString(data, JSONWriter.Feature.PrettyFormat).getBytes(StandardCharsets.UTF_8);
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                out.write(bytes);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("插件状态写入失败 " + file, e);
        }
    }

    // ------------------------------------------------------------ PluginDataRepository

    @Override
    public boolean isEnabled(String pluginId, String entityId) {
        synchronized (lock) {
            return Boolean.TRUE.equals(read().getOrDefault(pluginId, Map.of()).get(entityId));
        }
    }

    @Override
    public void setEnabled(String pluginId, String entityId, boolean enabled) {
        synchronized (lock) {
            Map<String, Map<String, Boolean>> data = read();
            // put 回旧值：null 表示原来没有这个键（状态真变了），否则不必写盘。
            boolean changed = enabled ? openEntities(data, pluginId).put(entityId, Boolean.TRUE) == null : drop(data, pluginId, entityId);
            if (changed) {
                write(data);
            }
        }
    }

    @Override
    public Map<String, Boolean> enabledMap(String pluginId, List<String> entityIds) {
        List<String> ids = entityIds == null ? List.of() : entityIds;
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, false);
        }
        synchronized (lock) {
            Map<String, Boolean> recorded = read().getOrDefault(pluginId, Map.of());
            for (String id : ids) {
                if (Boolean.TRUE.equals(recorded.get(id))) {
                    out.put(id, true);
                }
            }
        }
        return out;
    }

    @Override
    public List<String> enabledEntityIds(String pluginId) {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : read().getOrDefault(pluginId, Map.of()).entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    out.add(entry.getKey());
                }
            }
            return out;
        }
    }
}
