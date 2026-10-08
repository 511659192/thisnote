package com.thisnode.ext.notes.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 元数据的 JSON 编解码。
 *
 * <p>只管字符串 ↔ 对象；文件的读写（含原子替换）归 {@link NoteStore}。分成两层是因为 meta 文件
 * 是用户可见、可手改的：容错策略（未知字段忽略、字段缺失用默认值）属于格式，而"写坏了怎么办"
 * 属于落盘。
 */
public final class MetaJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 用户手改 meta 是预期行为（这正是文件权威的意义），多写的键不该让加载失败。
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private MetaJson() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化 " + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * 单行 JSON。
     *
     * <p>给"要塞进 md 一行注释里"的元数据用：卡片 meta 写进文件头部时必须是单行，
     * 否则一段元数据会把文件拉出十几行，人打开文件第一眼看到的就是它。
     */
    public static String toCompactJson(Object value) {
        try {
            return MAPPER.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化 " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    /** 读取 JSON 数组（分组清单、操作日志台账这类"一个文件装一列"的内容）。 */
    public static <T> java.util.List<T> listOf(String json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructCollectionType(java.util.List.class, type));
    }

    /** 读取 {@code {"键": "值"}} 形式的字符串映射（标签颜色、笔记侧设置）。 */
    public static Map<String, String> stringMap(String json) throws IOException {
        return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
        });
    }

    public static List<Map<String, Object>> listOfMaps(String json) throws IOException {
        return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
        });
    }
}
