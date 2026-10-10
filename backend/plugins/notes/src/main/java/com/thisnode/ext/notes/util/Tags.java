package com.thisnode.ext.notes.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签名的规范化与清单清理：标签的身份就是它规范化之后的名字，规则只该有一份。
 *
 * <p>放在 util 而不是某个仓储里：台账（{@code tags.json}）与笔记 meta 的 {@code tags} 都要按同一套
 * 规则认名字，而那两个仓储之间不该为了共用这点规则互相依赖。
 */
public final class Tags {

    private Tags() {
    }

    /** 去掉前导 {@code #}、压掉多余空白、截到 64 字符。 */
    public static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        String n = rawName.trim();
        while (n.startsWith("#")) {
            n = n.substring(1).trim();
        }
        n = n.replaceAll("\\s+", " ");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    /** 逐个规范化、丢掉空名、按忽略大小写去重（保留首次出现的写法）。 */
    public static List<String> cleaned(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String raw : names) {
            String name = normalize(raw);
            if (!name.isEmpty() && !contains(out, name)) {
                out.add(name);
            }
        }
        return out;
    }

    public static boolean contains(List<String> names, String name) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> sorted(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
