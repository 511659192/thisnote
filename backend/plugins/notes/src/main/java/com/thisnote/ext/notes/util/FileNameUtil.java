package com.thisnote.ext.notes.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 按标题派生的文件名：在目录里找 {@code prefix_1.ext、prefix_2.ext…} 中第一个可用的名字。
 */
public final class FileNameUtil {

    private FileNameUtil() {
    }

    /**
     * @param current 已存在但可以直接沿用的文件名（例如改名时旧文件自己占着这个编号）；没有则传 null
     * @return 第一个未被占用（或等于 current）的文件名
     */
    public static String nextAvailable(Path directory,
                                       String prefix,
                                       String extension,
                                       String current) {
        for (int number = 1; ; number++) {
            String name = prefix + "_" + number + extension;
            if (name.equals(current) || Files.notExists(directory.resolve(name))) {
                return name;
            }
        }
    }
}
