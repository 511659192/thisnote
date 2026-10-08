package com.thisnode.plugin.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 插件状态与配置的裸读写：**一个 Properties 文件**（默认 {@code <data-root>/plugin-data.json}）。
 *
 * <p><b>为什么单独一层：</b>插件要读自己的状态与配置，但 {@link PluginStateService} 依赖
 * 平台的插件注册表（要在状态翻转时触发钩子），而注册表装着所有插件——插件直接注入状态服务
 * 就会构成 Spring 装不出来的环。所以把「读写 KV」和「翻转时通知谁」拆开：
 * 插件只依赖本类，状态服务在此之上加钩子。
 *
 * <p><b>为什么是文件、为什么是 Properties：</b>它的前身是 {@code plugin_data} 表。笔记数据改为
 * 本地文件权威之后，这是平台里最后一张被真正读写的表 —— 换成文件后，数据库里不再有任何被
 * 应用读写的表。用 Properties 而不是 JSON，是因为这个 KV 本来就是扁平的字符串键值，
 * 而**契约层的依赖必须保持最小**（plugin-api 不引入 Jackson 之类的实现技术栈）。
 * 方法签名与表时代一一对应，使用者（状态服务、收藏、记忆曲线）零改动。
 *
 * <p>实例由主应用提供（构造参数是文件路径）；插件经 SBP 的 {@code importBean} 拿到主容器的实例。
 */
public class PluginDataStore {

    /** 底座自己管理的键：全局启停与 per-entity 开关都用它。 */
    public static final String ENABLED_KEY = "enabled";

    /** entityType：插件级状态（entityId = pluginId）。 */
    public static final String ENTITY_PLUGIN = "plugin";

    /** entityType：插件配置（entityId = pluginId，key = 配置项）。 */
    public static final String ENTITY_CONFIG = "config";

    private final Path file;
    private final Object lock = new Object();
    private Map<String, Map<String, String>> data;

    /** @param file 状态文件的位置（由主应用按数据根目录决定）。 */
    public PluginDataStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** 桶键：{@code pluginId|entityType|entityId} → 该实体的全部键值。 */
    private static String bucketKey(String pluginId, String entityType, String entityId) {
        return pluginId + "|" + entityType + "|" + entityId;
    }

    private Map<String, Map<String, String>> data() {
        Map<String, Map<String, String>> snapshot = data;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (lock) {
            if (data == null) {
                load();
            }
            return data;
        }
    }

    /** 磁盘格式是扁平的：{@code pluginId|entityType|entityId|key = value}。 */
    private void load() {
        data = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file, StandardOpenOption.READ)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            // 文件是用户可见可手改的：一个写坏的字符不该让所有插件起不来。当空库起，下次写回覆盖。
            return;
        }
        for (String name : props.stringPropertyNames()) {
            String[] parts = name.split("\\|", 4);
            if (parts.length != 4) {
                continue;
            }
            data.computeIfAbsent(parts[0] + "|" + parts[1] + "|" + parts[2],
                    k -> new LinkedHashMap<>()).put(parts[3], props.getProperty(name));
        }
    }

    private void save() {
        Properties props = new Properties();
        for (Map.Entry<String, Map<String, String>> bucket : data.entrySet()) {
            for (Map.Entry<String, String> kv : bucket.getValue().entrySet()) {
                props.setProperty(bucket.getKey() + "|" + kv.getKey(),
                        kv.getValue() == null ? "" : kv.getValue());
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                props.store(out, "thisnode plugin state: pluginId|entityType|entityId|key = value");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("插件状态写入失败 " + file, e);
        }
    }

    private Map<String, String> bucket(String pluginId, String entityType, String entityId) {
        return data().computeIfAbsent(bucketKey(pluginId, entityType, entityId),
                k -> new LinkedHashMap<>());
    }

    // ------------------------------------------------------------ 读写（签名同表时代）

    public String value(String pluginId, String entityType, String entityId, String key) {
        synchronized (lock) {
            return bucket(pluginId, entityType, entityId).get(key);
        }
    }

    /** 某实体下的全部键值（一次取整份）。 */
    public Map<String, String> values(String pluginId, String entityType, String entityId) {
        synchronized (lock) {
            return new LinkedHashMap<>(bucket(pluginId, entityType, entityId));
        }
    }

    public void put(String pluginId, String entityType, String entityId, String key, String value) {
        synchronized (lock) {
            bucket(pluginId, entityType, entityId).put(key, value);
            save();
        }
    }

    public void remove(String pluginId, String entityType, String entityId, String key) {
        synchronized (lock) {
            Map<String, String> bucket = bucket(pluginId, entityType, entityId);
            bucket.remove(key);
            if (bucket.isEmpty()) {
                data().remove(bucketKey(pluginId, entityType, entityId));
            }
            save();
        }
    }

    public boolean isEnabled(String pluginId, String entityType, String entityId) {
        return Boolean.parseBoolean(value(pluginId, entityType, entityId, ENABLED_KEY));
    }

    public void setEnabled(String pluginId, String entityType, String entityId, boolean enabled) {
        if (enabled) {
            put(pluginId, entityType, entityId, ENABLED_KEY, "true");
        } else {
            remove(pluginId, entityType, entityId, ENABLED_KEY);
        }
    }

    /** 批量查询「这些实体里哪些开着」。 */
    public Map<String, Boolean> enabledMap(String pluginId, String entityType,
                                           List<String> entityIds) {
        Map<String, Boolean> out = new HashMap<>();
        List<String> ids = entityIds == null ? List.of() : entityIds;
        for (String id : ids) {
            out.put(id, false);
        }
        synchronized (lock) {
            for (String id : ids) {
                if (Boolean.parseBoolean(bucket(pluginId, entityType, id).get(ENABLED_KEY))) {
                    out.put(id, true);
                }
            }
        }
        return out;
    }

    /**
     * 某插件在某类实体上「开着」的全部 id（不限定候选集）。
     *
     * <p>与 {@link #enabledMap} 的方向相反：那个是「这些实体里哪些开着」，这个是「开着的都有谁」——
     * 插件画自己的列表（例如收藏页）时需要的正是后者。
     */
    public List<String> enabledEntityIds(String pluginId, String entityType) {
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, Map<String, String>> entry : data().entrySet()) {
                String[] parts = entry.getKey().split("\\|", -1);
                if (parts.length == 3 && parts[0].equals(pluginId) && parts[1].equals(entityType)
                        && Boolean.parseBoolean(entry.getValue().get(ENABLED_KEY))) {
                    out.add(parts[2]);
                }
            }
        }
        return out;
    }
}
