package com.thisnode.ext.baidusync.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.UUID;

@Service
public class BaiduPanClient {

    private static final String FILE_API = "https://pan.baidu.com/rest/2.0/xpan/file";
    private static final String META_API = "https://pan.baidu.com/rest/2.0/xpan/multimedia";
    private static final String UPLOAD_API = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2";
    private static final int PAGE_SIZE = 1000;
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;
    private final HttpClient http;
    private final String fileApi;

    public BaiduPanClient() {
        this(FILE_API);
    }

    BaiduPanClient(String fileApi) {
        this.fileApi = fileApi;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
                String listedMd5 = value(row.getString("md5")).trim();
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

    public boolean directoryExists(String accessToken,
                                    String directoryPath) {
        int slash = directoryPath.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : directoryPath.substring(0, slash);
        List<BaiduPanEntry> items;
        try {
            items = listDirectory(accessToken, parent);
        } catch (BaiduPanException e) {
            if (e.errno() == -9) {
                return false;
            }
            throw e;
        }
        return items.stream()
                .filter(item -> item.path().equals(directoryPath))
                .findFirst()
                .map(item -> {
                    if (!item.directory()) {
                        throw new IllegalStateException("百度网盘目标路径不是目录：" + directoryPath);
                    }
                    return true;
                })
                .orElse(false);
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

    public void delete(String accessToken,
                       String path) {
        Map<String, String> query = Map.of(
                "method", "filemanager",
                "opera", "delete",
                "access_token", accessToken);
        String fileList = JSON.toJSONString(List.of(Map.of("path", path)));
        Map<String, String> form = Map.of("async", "0", "filelist", fileList);
        try {
            checkFileManagerErrors(postJson(fileApi + "?" + query(query), form));
        } catch (BaiduPanException e) {
            throw new IllegalStateException("删除百度网盘文件失败：" + path + "；" + e.getMessage(), e);
        }
    }

    public void moveOverwrite(String accessToken,
                              String sourcePath,
                              String destinationDirectory,
                              String filename) {
        String destinationPath = destinationDirectory + "/" + filename;
        String action = "百度网盘覆盖移动失败：" + sourcePath + " -> " + destinationPath;
        try {
            moveFile(accessToken, sourcePath, destinationDirectory, filename, true);
        } catch (BaiduPanException e) {
            if (e.apiErrno() != 12 || e.itemErrno() != -8) {
                throw new IllegalStateException(action + "；" + e.getMessage(), e);
            }
            try {
                moveWithBackup(accessToken, sourcePath, destinationPath, destinationDirectory, filename);
            } catch (RuntimeException replacementError) {
                throw new IllegalStateException(action + "；" + replacementError.getMessage(), replacementError);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException(action + "；" + e.getMessage(), e);
        }
    }

    private void moveWithBackup(String accessToken,
                                String sourcePath,
                                String destinationPath,
                                String destinationDirectory,
                                String filename) {
        String stagingDirectory = sourcePath.substring(0, sourcePath.lastIndexOf('/'));
        String backupFilename = "backup-" + UUID.randomUUID() + ".tmp";
        String backupPath = stagingDirectory + "/" + backupFilename;
        moveFile(accessToken, destinationPath, stagingDirectory, backupFilename, false);
        try {
            moveFile(accessToken, sourcePath, destinationDirectory, filename, false);
        } catch (RuntimeException moveError) {
            try {
                moveFile(accessToken, backupPath, destinationDirectory, filename, false);
            } catch (RuntimeException restoreError) {
                moveError.addSuppressed(restoreError);
                throw new IllegalStateException("新文件移动失败且旧文件恢复失败；旧文件保留在备份路径："
                        + backupPath + "；恢复错误：" + restoreError.getMessage(), moveError);
            }
            throw moveError;
        }
        delete(accessToken, backupPath);
    }

    private void moveFile(String accessToken,
                          String sourcePath,
                          String destinationDirectory,
                          String filename,
                          boolean overwrite) {
        Map<String, String> query = Map.of(
                "method", "filemanager",
                "opera", "move",
                "access_token", accessToken);
        Map<String, String> item = new LinkedHashMap<>();
        item.put("path", sourcePath);
        item.put("dest", destinationDirectory);
        item.put("newname", filename);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("async", "0");
        if (overwrite) {
            item.put("ondup", "overwrite");
            form.put("ondup", "overwrite");
        }
        form.put("filelist", JSON.toJSONString(List.of(item)));
        checkFileManagerErrors(postJson(fileApi + "?" + query(query), form));
    }

    private void checkFileManagerErrors(JSONObject result) {
        JSONArray info = result.getJSONArray("info");
        if (info == null) {
            return;
        }
        int apiErrno = result.getIntValue("errno");
        for (int i = 0; i < info.size(); i++) {
            JSONObject item = info.getJSONObject(i);
            int itemErrno = item.getIntValue("errno");
            if (itemErrno != 0) {
                throw new BaiduPanException(apiErrno, itemErrno, item.getString("path"));
            }
        }
    }

    public void download(String accessToken,
                         BaiduPanEntry item,
                         Path destination) {
        JSONObject metadata;
        try {
            metadata = getJson(META_API, Map.of(
                    "method", "filemetas",
                    "access_token", accessToken,
                    "fsids", JSON.toJSONString(List.of(item.fsId())),
                    "dlink", "1"));
        } catch (BaiduPanException e) {
            throw new IllegalStateException("获取百度网盘下载地址失败：" + item.path() + "；" + e.getMessage(), e);
        }
        JSONArray list = metadata.getJSONArray("list");
        String downloadUrl = list == null || list.isEmpty() ? null : list.getJSONObject(0).getString("dlink");
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalStateException("百度网盘没有返回文件下载地址：" + item.path());
        }
        downloadUrl += downloadUrl.contains("?") ? "&access_token=" + encode(accessToken)
                                                  : "?access_token=" + encode(accessToken);
        Path parent = destination.getParent();
        Path temporary = parent.resolve(destination.getFileName() + ".baidu-sync.tmp");
        try {
            Files.createDirectories(parent);
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", "pan.baidu.com")
                    .GET()
                    .build();
            HttpResponse<Path> response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(temporary, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("百度网盘下载失败，HTTP " + response.statusCode());
            }
            String expectedMd5 = response.headers().firstValue("Content-MD5")
                    .map(this::normalizeMd5)
                    .filter(value -> !value.isBlank())
                    .orElseGet(() -> normalizeMd5(item.md5()));
            if (expectedMd5.isBlank() && !item.md5().isBlank()) {
                throw new IllegalStateException("百度网盘返回的文件 MD5 格式无效：" + item.path());
            }
            if (!expectedMd5.isBlank() && !expectedMd5.equalsIgnoreCase(fileMd5(temporary))) {
                throw new IllegalStateException("百度网盘下载内容校验失败：" + item.path());
            }
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (item.modifiedAt() > 0) {
                Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(item.modifiedAt()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度网盘下载被中断：" + item.path(), e);
        } catch (IOException e) {
            throw new IllegalStateException("百度网盘下载失败：" + item.path(), e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
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
            precreate.put("rtype", "0");
            precreate.put("block_list", JSON.toJSONString(blocks));
            JSONObject prepared = postJson(fileApi + "?" + query(Map.of(
                    "method", "precreate", "access_token", accessToken)), precreate);
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
            create.put("rtype", "0");
            create.put("local_mtime", Long.toString(modifiedAt / 1000));
            postJson(fileApi + "?" + query(Map.of("method", "create", "access_token", accessToken)), create);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("User-Agent", "thisnote-baidu-sync")
                .POST(HttpRequest.BodyPublishers.ofString(query(values)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return checkJson(response.statusCode(), response.body(), "百度网盘写入失败");
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

    private String fileMd5(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 运行环境不支持 MD5", e);
        }
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
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"part\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造上传分片失败", e);
        }
    }

    private static String query(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String md5(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder value = new StringBuilder(32);
            for (byte part : digest) {
                value.append(String.format("%02x", part));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 运行环境不支持 MD5", e);
        }
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static long modifiedAt(JSONObject row) {
        long local = row.getLongValue("local_mtime");
        return (local > 0 ? local : row.getLongValue("server_mtime")) * 1000;
    }

}
