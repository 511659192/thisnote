package com.thisnote.plugin.api.util;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 读取图片的像素尺寸（宽、高）。
 *
 * <p>优先用 {@link ImageIO}（png/jpg 等标准格式）；WebP 没有内置解码器，
 * 退回到解析 RIFF 头（VP8 / VP8L / VP8X 三种块）。
 */
@Slf4j
public final class ImageSize {

    private ImageSize() {
    }

    /**
     * @return {@code [width, height]}；读不出来时返回 {@code null}
     */
    public static int[] of(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        if (width > 0 && height > 0) {
                            return new int[]{width, height};
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception e) {
            // 交给 WebP 头解析兜底
        }
        int[] fallback = webp(file);
        if (fallback == null) {
            log.error("无法读取图片尺寸：{}", file);
        }
        return fallback;
    }

    /**
     * 解析 WebP 的 RIFF 头；不是 WebP 或头部不完整时返回 null。
     */
    static int[] webp(Path file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] riff = new byte[12];
            in.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F'
                    || riff[8] != 'W' || riff[9] != 'E' || riff[10] != 'B' || riff[11] != 'P') {
                return null;
            }
            while (true) {
                byte[] header = new byte[8];
                in.readFully(header);
                String fourcc = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
                int size = (header[4] & 0xff)
                        | (header[5] & 0xff) << 8
                        | (header[6] & 0xff) << 16
                        | (header[7] & 0xff) << 24;
                if ("VP8X".equals(fourcc)) {
                    byte[] data = new byte[10];
                    in.readFully(data);
                    return new int[]{1 + littleEndian24(data, 4), 1 + littleEndian24(data, 7)};
                }
                if ("VP8L".equals(fourcc)) {
                    byte[] data = new byte[5];
                    in.readFully(data);
                    if ((data[0] & 0xff) != 0x2f) {
                        return null;
                    }
                    int width = 1 + (((data[1] & 0xff) | (data[2] & 0xff) << 8) & 0x3fff);
                    int height = 1 + (((data[2] & 0xff) >> 6 | (data[3] & 0xff) << 2 | (data[4] & 0xff) << 10) & 0x3fff);
                    return new int[]{width, height};
                }
                if ("VP8 ".equals(fourcc)) {
                    byte[] data = new byte[10];
                    in.readFully(data);
                    if ((data[3] & 0xff) != 0x9d || (data[4] & 0xff) != 0x01 || (data[5] & 0xff) != 0x2a) {
                        return null;
                    }
                    int width = ((data[6] & 0xff) | (data[7] & 0xff) << 8) & 0x3fff;
                    int height = ((data[8] & 0xff) | (data[9] & 0xff) << 8) & 0x3fff;
                    return new int[]{width, height};
                }
                skipFully(in, size + (size & 1));
            }
        } catch (EOFException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int littleEndian24(byte[] data,
                                      int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8 | (data[offset + 2] & 0xff) << 16;
    }

    private static void skipFully(InputStream in,
                                  int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                throw new EOFException("跳过 WebP 数据块失败");
            }
            remaining -= (int) skipped;
        }
    }
}
