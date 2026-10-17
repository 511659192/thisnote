package com.thisnode.ext.baidusync.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnode.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class BaiduPanClient {

    private final FileHelper fileHelper;

    public static final int FILE_MANAGER_BATCH_SIZE = 50;
    private static final String UPLOAD_RTYPE_OVERWRITE = "3";

    private static final String FILE_API = "https://pan.baidu.com/rest/2.0/xpan/file";
    private static final String META_API = "https://pan.baidu.com/rest/2.0/xpan/multimedia";
    private static final String UPLOAD_API = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2";
    private static final int PAGE_SIZE = 1000;
    private static final int FILE_META_BATCH_SIZE = 50;
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;
    private final HttpClient http;
    private final String fileApi;

    @Autowired
    public BaiduPanClient(FileHelper fileHelper) {
        this(FILE_API, fileHelper);
    }

    BaiduPanClient(String fileApi,
                   FileHelper fileHelper) {
        this.fileApi = fileApi;
        this.fileHelper = fileHelper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public List<BaiduPanEntry> listDirectory(String accessToken,
                                             String remoteDirectory) {
        List<BaiduPanEntry> items = new ArrayList<>();
        for (int start = 0; ; start += PAGE_SIZE) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("method", "list");
            query.put("access_token", accessToken);
            query.put("dir", remoteDirectory);
            query.put("start", Integer.toString(start));
            query.put("limit", Integer.toString(PAGE_SIZE));
            query.put("web", "0");
            JSONObject body;
            try {
                body = getJson(fileApi, query);
            } catch (BaiduPanException e) {
                if (e.errno() == -9) {
                    throw e;
                }
                throw new IllegalStateException("读取百度网盘目录失败：" + remoteDirectory + "；" + e.getMessage(), e);
            }
            JSONArray list = body.getJSONArray("list");
            if (list == null) {
                throw new IllegalStateException("百度网盘未返回文件列表，已停止同步以避免误删文件：" + remoteDirectory);
            }
            if (list.isEmpty()) {
                break;
            }
            for (int i = 0; i < list.size(); i++) {
                JSONObject row = list.getJSONObject(i);
                String path = row.getString("path");
                if (path == null || path.isBlank()) {
                    String name = row.getString("server_filename");
                    if (name == null || name.isBlank()) {
                        throw new IllegalStateException("百度网盘返回的文件缺少路径和名称：" + remoteDirectory);
                    }
                    path = remoteDirectory + "/" + name;
                }
                String listedMd5 = Objects.toString(row.getString("md5"), "").trim();
                String normalizedMd5 = normalizeMd5(listedMd5);
                items.add(new BaiduPanEntry(path,
                                            row.getIntValue("isdir") == 1,
                                            row.getLongValue("size"),
                                            normalizedMd5.isBlank() ? listedMd5 : normalizedMd5,
                                            modifiedAt(row),
                                            row.getLongValue("fs_id")));
            }
            if (list.size() < PAGE_SIZE) {
                break;
            }
        }
        return items;
    }

    public void createDirectory(String accessToken,
                                String path) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("path", path);
        form.put("isdir", "1");
        form.put("size", "0");
        form.put("block_list", "[]");
        form.put("rtype", "0");
        postJson(fileApi + "?" + query(Map.of("method", "create", "access_token", accessToken)), form);
    }

    public List<FileManagerFailure> deleteBatch(String accessToken,
                                                List<String> paths) {
        requireFileManagerBatchSize(paths.size());
        List<Map<String, String>> items = paths.stream().map(path -> Map.of("path", path)).toList();
        return fileManagerFailures(fileManager(accessToken, "delete", items));
    }

    private JSONObject fileManager(String accessToken,
                                   String operation,
                                   List<Map<String, String>> items) {
        Map<String, String> query = Map.of("method", "filemanager", "opera", operation, "access_token", accessToken);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("async", "0");
        form.put("filelist", JSON.toJSONString(items));
        return postFileManagerJson(fileApi + "?" + query(query), form);
    }

    private List<FileManagerFailure> fileManagerFailures(JSONObject result) {
        int apiErrno = result.getIntValue("errno");
        JSONArray info = result.getJSONArray("info");
        List<FileManagerFailure> failures = new ArrayList<>();
        if (info != null) {
            for (int i = 0; i < info.size(); i++) {
                JSONObject item = info.getJSONObject(i);
                int itemErrno = item.getIntValue("errno");
                if (itemErrno != 0) {
                    failures.add(new FileManagerFailure(item.getString("path"), apiErrno, itemErrno));
                }
            }
        }
        if (apiErrno != 0 && failures.isEmpty()) {
            throw new BaiduPanException(apiErrno);
        }
        return failures;
    }

    private void requireFileManagerBatchSize(int size) {
        if (size > FILE_MANAGER_BATCH_SIZE) {
            throw new IllegalArgumentException("百度网盘单次文件管理操作最多处理 " + FILE_MANAGER_BATCH_SIZE + " 个文件");
        }
    }

    public Map<Long, String> downloadUrls(String accessToken,
                                          List<BaiduPanEntry> items) {
        Map<Long, String> urls = new LinkedHashMap<>();
        for (int start = 0; start < items.size(); start += FILE_META_BATCH_SIZE) {
            List<BaiduPanEntry> batch = items.subList(start, Math.min(start + FILE_META_BATCH_SIZE, items.size()));
            List<Long> fsIds = batch.stream().map(BaiduPanEntry::fsId).toList();
            JSONObject metadata;
            try {
                metadata = getJson(META_API,
                                   Map.of("method", "filemetas", "access_token", accessToken, "fsids", JSON.toJSONString(fsIds), "dlink", "1"));
            } catch (BaiduPanException e) {
                throw new IllegalStateException("获取百度网盘批量下载地址失败：" + e.getMessage(), e);
            }
            JSONArray list = metadata.getJSONArray("list");
            if (list == null) {
                throw new IllegalStateException("百度网盘未返回批量下载地址");
            }
            for (int i = 0; i < list.size(); i++) {
                JSONObject row = list.getJSONObject(i);
                long fsId = row.getLongValue("fs_id");
                String downloadUrl = row.getString("dlink");
                if (fsId <= 0 || downloadUrl == null || downloadUrl.isBlank()) {
                    throw new IllegalStateException("百度网盘未返回文件下载地址");
                }
                urls.put(fsId, downloadUrl);
            }
            for (BaiduPanEntry item : batch) {
                if (!urls.containsKey(item.fsId())) {
                    throw new IllegalStateException("百度网盘未返回文件下载地址：" + item.path());
                }
            }
        }
        return urls;
    }

    public void downloadWithUrl(String accessToken,
                                BaiduPanEntry item,
                                Path destination,
                                String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalStateException("百度网盘没有返回文件下载地址：" + item.path());
        }
        String encodedAccessToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        downloadUrl += downloadUrl.contains("?") ? "&access_token=" + encodedAccessToken : "?access_token=" + encodedAccessToken;
        try {
            MessageDigest digest = newMd5Digest();
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", "pan.baidu.com")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                body.close();
                throw new IllegalStateException("百度网盘下载失败，HTTP " + response.statusCode());
            }
            String expectedMd5 = response.headers().firstValue("Content-MD5").map(this::normalizeMd5).filter(value -> !value.isBlank()).orElse("");
            fileHelper.fileWritten(destination,
                                   new DownloadValidationInputStream(body, item.size(), expectedMd5, item.path(), digest),
                                   item.modifiedAt());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度网盘下载被中断：" + item.path(), e);
        } catch (UncheckedIOException e) {
            throw new IllegalStateException("百度网盘下载失败：" + item.path(), e.getCause());
        } catch (IOException e) {
            throw new IllegalStateException("百度网盘下载失败：" + item.path(), e);
        }
    }

    public void upload(String accessToken,
                       Path source,
                       String remotePath) {
        try {
            long size = Files.size(source);
            long modifiedAt = Files.getLastModifiedTime(source).toMillis();
            List<String> blocks = blockMd5s(source, size);
            Map<String, String> precreate = new LinkedHashMap<>();
            precreate.put("path", remotePath);
            precreate.put("size", Long.toString(size));
            precreate.put("isdir", "0");
            precreate.put("autoinit", "1");
            precreate.put("rtype", UPLOAD_RTYPE_OVERWRITE);
            precreate.put("block_list", JSON.toJSONString(blocks));
            JSONObject prepared = postJson(fileApi + "?" + query(Map.of("method", "precreate", "access_token", accessToken)), precreate);
            String uploadId = prepared.getString("uploadid");
            if (uploadId == null || uploadId.isBlank()) {
                throw new IllegalStateException("百度网盘未返回上传任务编号：" + remotePath);
            }

            for (int part = 0; part < blocks.size(); part++) {
                byte[] data = readBlock(source, part, size);
                if (!blocks.get(part).equals(md5(data))) {
                    throw new IllegalStateException("同步期间文件发生变化，请重试：" + source);
                }
                uploadPart(accessToken, remotePath, uploadId, part, data);
            }

            Map<String, String> create = new LinkedHashMap<>();
            create.put("path", remotePath);
            create.put("size", Long.toString(size));
            create.put("isdir", "0");
            create.put("uploadid", uploadId);
            create.put("block_list", JSON.toJSONString(blocks));
            create.put("rtype", UPLOAD_RTYPE_OVERWRITE);
            create.put("local_mtime", Long.toString(modifiedAt / 1000));
            JSONObject created = postJson(fileApi + "?" + query(Map.of("method", "create", "access_token", accessToken)), create);
            String createdPath = created.getString("path");
            if (createdPath != null && !remotePath.equals(createdPath)) {
                throw new IllegalStateException("百度网盘上传后路径被重命名：" + remotePath + " -> " + createdPath);
            }
            if (Files.size(source) != size || Files.getLastModifiedTime(source).toMillis() != modifiedAt) {
                throw new IllegalStateException("同步期间文件发生变化，已上传旧版本，请再次同步：" + source);
            }
        } catch (BaiduPanException e) {
            throw new IllegalStateException("上传百度网盘文件失败：" + remotePath + "；" + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("读取待上传文件失败：" + source, e);
        }
    }

    private void uploadPart(String accessToken,
                            String path,
                            String uploadId,
                            int part,
                            byte[] bytes) {
        String boundary = "thisnote" + Long.toHexString(System.nanoTime());
        byte[] body = multipart(boundary, bytes);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("method", "upload");
        query.put("access_token", accessToken);
        query.put("type", "tmpfile");
        query.put("path", path);
        query.put("uploadid", uploadId);
        query.put("partseq", Integer.toString(part));
        HttpRequest request = HttpRequest.newBuilder(URI.create(UPLOAD_API + "?" + query(query)))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkJson(response.statusCode(), response.body(), "上传文件分片失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度网盘上传被中断");
        } catch (IOException e) {
            throw new IllegalStateException("百度网盘上传请求失败", e);
        }
    }

    private JSONObject getJson(String baseUrl,
                               Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "?" + query(query)))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "thisnote-baidu-sync")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return checkJson(response.statusCode(), response.body(), "百度网盘查询失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度网盘请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接百度网盘", e);
        }
    }

    private JSONObject postJson(String url,
                                Map<String, String> values) {
        HttpResponse<String> response = postForm(url, values);
        return checkJson(response.statusCode(), response.body(), "百度网盘写入失败");
    }

    private JSONObject postFileManagerJson(String url,
                                           Map<String, String> values) {
        HttpResponse<String> response = postForm(url, values);
        JSONObject body;
        try {
            body = JSON.parseObject(response.body());
        } catch (RuntimeException e) {
            throw new IllegalStateException("百度网盘文件管理失败，HTTP " + response.statusCode());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body == null) {
            throw new IllegalStateException("百度网盘文件管理失败，HTTP " + response.statusCode());
        }
        return body;
    }

    private HttpResponse<String> postForm(String url,
                                          Map<String, String> values) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("User-Agent", "thisnote-baidu-sync")
                .POST(HttpRequest.BodyPublishers.ofString(query(values)))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度网盘请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接百度网盘", e);
        }
    }

    private JSONObject checkJson(int statusCode,
                                 String responseBody,
                                 String action) {
        JSONObject body;
        try {
            body = JSON.parseObject(responseBody);
        } catch (RuntimeException e) {
            throw new IllegalStateException(action + "，HTTP " + statusCode);
        }
        if (statusCode < 200 || statusCode >= 300 || body == null) {
            throw new IllegalStateException(action + "，HTTP " + statusCode);
        }
        int errno = body.getIntValue("errno");
        if (errno != 0) {
            JSONArray info = body.getJSONArray("info");
            if (info != null) {
                for (int i = 0; i < info.size(); i++) {
                    JSONObject item = info.getJSONObject(i);
                    int itemErrno = item.getIntValue("errno");
                    if (itemErrno != 0) {
                        throw new BaiduPanException(errno, itemErrno, item.getString("path"));
                    }
                }
            }
            throw new BaiduPanException(errno);
        }
        return body;
    }

    private List<String> blockMd5s(Path file,
                                   long size) throws IOException {
        List<String> md5s = new ArrayList<>();
        for (int part = 0; (long) part * BLOCK_SIZE < size; part++) {
            md5s.add(md5(readBlock(file, part, size)));
        }
        return md5s;
    }

    private String normalizeMd5(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.matches("(?i)[0-9a-f]{32}")) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = Base64.getDecoder().decode(normalized);
            return digest.length == 16 ? HexFormat.of().formatHex(digest) : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static final class DownloadValidationInputStream extends FilterInputStream {
        private final long expectedSize;
        private final String expectedMd5;
        private final String remotePath;
        private final MessageDigest digest;
        private long actualSize;
        private boolean validated;

        private DownloadValidationInputStream(InputStream input,
                                              long expectedSize,
                                              String expectedMd5,
                                              String remotePath,
                                              MessageDigest digest) {
            super(input);
            this.expectedSize = expectedSize;
            this.expectedMd5 = expectedMd5;
            this.remotePath = remotePath;
            this.digest = digest;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                validate();
            } else {
                digest.update((byte) value);
                actualSize++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes,
                        int offset,
                        int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count < 0) {
                validate();
            } else if (count > 0) {
                digest.update(bytes, offset, count);
                actualSize += count;
            }
            return count;
        }

        private void validate() throws IOException {
            if (validated) {
                return;
            }
            validated = true;
            if (actualSize != expectedSize) {
                throw new IOException("百度网盘下载大小不匹配：" + remotePath + "；期望 " + expectedSize + "，实际 " + actualSize);
            }
            String actualMd5 = HexFormat.of().formatHex(digest.digest());
            if (!expectedMd5.isBlank() && !expectedMd5.equalsIgnoreCase(actualMd5)) {
                throw new IOException("百度网盘下载内容校验失败：" + remotePath);
            }
        }
    }

    private byte[] readBlock(Path file,
                             int part,
                             long size) throws IOException {
        long offset = (long) part * BLOCK_SIZE;
        int length = (int) Math.min(BLOCK_SIZE, size - offset);
        byte[] bytes = new byte[length];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            }
            if (buffer.hasRemaining()) {
                throw new IOException("文件读取长度与预期不符");
            }
        }
        return bytes;
    }

    private byte[] multipart(String boundary,
                             byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 256);
            out.write(("--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"file\"; filename=\"part\"\r\n" + "Content-Type: application/octet-stream\r\n\r\n").getBytes(
                    StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造上传分片失败", e);
        }
    }

    private static String query(Map<String, String> values) {
        return values.entrySet()
                .stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                              + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String md5(byte[] bytes) {
        byte[] digest = newMd5Digest().digest(bytes);
        StringBuilder value = new StringBuilder(32);
        for (byte part : digest) {
            value.append(String.format("%02x", part));
        }
        return value.toString();
    }

    private static MessageDigest newMd5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 运行环境不支持 MD5", e);
        }
    }

    private static long modifiedAt(JSONObject row) {
        long local = row.getLongValue("local_mtime");
        return (local > 0 ? local : row.getLongValue("server_mtime")) * 1000;
    }

    public record FileManagerFailure(String path, int apiErrno, int itemErrno) {
        public BaiduPanException exception() {
            return new BaiduPanException(apiErrno, itemErrno, path);
        }
    }

}
