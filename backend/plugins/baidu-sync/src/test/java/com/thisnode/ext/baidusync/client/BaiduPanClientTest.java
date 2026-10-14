package com.thisnode.ext.baidusync.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Queue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduPanClientTest {

    private static final String REMOTE_ROOT = "/apps/thisnote/notes";
    private static final String STAGING_DIRECTORY = "._thisnode_baidu_sync_staging_64fd90c1";
    private static final String SOURCE_PATH = REMOTE_ROOT + "/" + STAGING_DIRECTORY + "/upload-f616c716-a7b4-4beb-9da6-4ac1ea0d506d.tmp";
    private static final String DESTINATION_PATH = REMOTE_ROOT + "/.id-file-index.json";

    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private BaiduPanClient client;

    @BeforeEach
    void startServer() throws IOException {
        responses.clear();
        requests.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", this::respond);
        server.start();
        client = new BaiduPanClient("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replacesExistingDestinationWhenMoveReturnsErrno12AndMinus8() {
        responses.add(conflict(SOURCE_PATH));
        responses.add(success());
        responses.add(success());
        responses.add(success());

        client.moveOverwrite("test-token", SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json");

        assertEquals(4, requests.size());
        assertMove(requests.get(0), SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json");
        assertEquals("overwrite", requests.get(0).form().get("ondup"));

        CapturedRequest backupMove = requests.get(1);
        JSONObject backupItem = item(backupMove);
        String backupPath = backupItem.getString("dest") + "/" + backupItem.getString("newname");
        assertEquals(DESTINATION_PATH, backupItem.getString("path"));
        assertEquals(REMOTE_ROOT + "/" + STAGING_DIRECTORY, backupItem.getString("dest"));
        assertTrue(backupItem.getString("newname").matches("backup-[0-9a-f-]{36}\\.tmp"));
        assertFalse(backupMove.form().containsKey("ondup"));

        assertMove(requests.get(2), SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json");
        assertFalse(requests.get(2).form().containsKey("ondup"));

        CapturedRequest deleteBackup = requests.get(3);
        assertEquals("delete", deleteBackup.query().get("opera"));
        JSONArray deletedItems = JSON.parseArray(deleteBackup.form().get("filelist"));
        assertEquals(backupPath, deletedItems.getJSONObject(0).getString("path"));
    }

    @Test
    void restoresExistingDestinationWhenMovingStagedFileFails() {
        responses.add(conflict(SOURCE_PATH));
        responses.add(success());
        responses.add("{\"errno\":12,\"info\":[{\"errno\":-3,\"path\":\"" + SOURCE_PATH + "\"}]}");
        responses.add(success());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.moveOverwrite("test-token", SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json"));

        assertTrue(error.getMessage().contains("百度网盘覆盖移动失败"));
        assertEquals(4, requests.size());
        JSONObject restoreItem = item(requests.get(3));
        assertEquals(DESTINATION_PATH, restoreItem.get("dest") + "/" + restoreItem.get("newname"));
        assertTrue(restoreItem.getString("path").startsWith(
                REMOTE_ROOT + "/" + STAGING_DIRECTORY + "/backup-"));
        assertTrue(restoreItem.getString("path").endsWith(".tmp"));
        assertFalse(requests.get(3).form().containsKey("ondup"));
    }

    @Test
    void retriesReplacementMoveWhenRateLimited() {
        responses.add(conflict(SOURCE_PATH));
        responses.add(success());
        responses.add("{\"errno\":12,\"info\":[{\"errno\":31034,\"path\":\"" + SOURCE_PATH + "\"}]}");
        responses.add(success());
        responses.add(success());

        client.moveOverwrite("test-token", SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json");

        assertEquals(5, requests.size());
        assertMove(requests.get(3), SOURCE_PATH, REMOTE_ROOT, ".id-file-index.json");
        assertEquals("delete", requests.get(4).query().get("opera"));
    }

    @Test
    void fetchesDownloadUrlsInBatchesOfFifty() {
        JSONArray firstBatch = new JSONArray();
        for (long fsId = 1; fsId <= 50; fsId++) {
            firstBatch.add(Map.of("fs_id", fsId, "dlink", "https://download/" + fsId));
        }
        JSONArray secondBatch = new JSONArray();
        secondBatch.add(Map.of("fs_id", 51, "dlink", "https://download/51"));
        responses.add(JSON.toJSONString(Map.of("errno", 0, "list", firstBatch)));
        responses.add(JSON.toJSONString(Map.of("errno", 0, "list", secondBatch)));
        List<BaiduPanEntry> entries = java.util.stream.LongStream.rangeClosed(1, 51)
                .mapToObj(fsId -> new BaiduPanEntry(REMOTE_ROOT + "/" + fsId, false,
                        1, "", 0, fsId))
                .toList();

        Map<Long, String> urls = client.downloadUrls("test-token", entries);

        assertEquals(51, urls.size());
        assertEquals("https://download/1", urls.get(1L));
        assertEquals("https://download/51", urls.get(51L));
        assertEquals(2, requests.size());
        assertEquals(50, JSON.parseArray(requests.get(0).query().get("fsids")).size());
        assertEquals(1, JSON.parseArray(requests.get(1).query().get("fsids")).size());
    }

    @Test
    void deletesMultipleFilesWithOneFileManagerRequest() {
        responses.add("{\"errno\":0,\"info\":[{\"errno\":0},{\"errno\":0}]}");

        List<BaiduPanClient.FileManagerFailure> failures = client.deleteBatch("test-token",
                List.of(REMOTE_ROOT + "/one", REMOTE_ROOT + "/two"));

        assertTrue(failures.isEmpty());
        assertEquals(1, requests.size());
        CapturedRequest request = requests.get(0);
        assertEquals("delete", request.query().get("opera"));
        assertEquals(2, JSON.parseArray(request.form().get("filelist")).size());
    }

    @Test
    void returnsPerFileErrorsFromBatchMove() {
        responses.add("{\"errno\":12,\"info\":[{\"errno\":0,\"path\":\"source-one\"},"
                + "{\"errno\":-8,\"path\":\"source-two\"}]}");
        List<BaiduPanClient.FileManagerMove> moves = List.of(
                new BaiduPanClient.FileManagerMove("source-one", REMOTE_ROOT, "one"),
                new BaiduPanClient.FileManagerMove("source-two", REMOTE_ROOT, "two"));

        List<BaiduPanClient.FileManagerFailure> failures = client.moveOverwriteBatch("test-token", moves);

        assertEquals(1, failures.size());
        assertEquals("source-two", failures.get(0).path());
        assertEquals(12, failures.get(0).apiErrno());
        assertEquals(-8, failures.get(0).itemErrno());
        assertEquals("move", requests.get(0).query().get("opera"));
        assertEquals("overwrite", requests.get(0).form().get("ondup"));
        assertEquals(2, JSON.parseArray(requests.get(0).form().get("filelist")).size());
    }

    @Test
    void normalizesBase64Md5FromDirectoryListing() throws NoSuchAlgorithmException {
        byte[] content = "unchanged local content".getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("MD5").digest(content);
        String expectedMd5 = HexFormat.of().formatHex(digest);
        String listedMd5 = Base64.getEncoder().encodeToString(digest);
        responses.add("{\"errno\":0,\"list\":[{\"path\":\"" + REMOTE_ROOT
                + "/cards/unchanged.md\",\"isdir\":0,\"size\":" + content.length
                + ",\"md5\":\"" + listedMd5 + "\"}]}");

        List<BaiduPanEntry> entries = client.listDirectory("test-token", REMOTE_ROOT);

        assertEquals(expectedMd5, entries.get(0).md5());
    }

    @Test
    void preservesUnrecognizedNonblankDigestFromDirectoryListing() {
        String listedDigest = "baidu-opaque-digest";
        responses.add("{\"errno\":0,\"list\":[{\"path\":\"" + REMOTE_ROOT
                + "/.id-file-index.json\",\"isdir\":0,\"size\":1,\"md5\":\""
                + listedDigest + "\"}]}");

        List<BaiduPanEntry> entries = client.listDirectory("test-token", REMOTE_ROOT);

        assertEquals(listedDigest, entries.get(0).md5());
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String requestBodyText = new String(requestBody, StandardCharsets.UTF_8);
        Map<String, String> form = requestBodyText.isBlank() ? Map.of() : parseForm(requestBodyText);
        requests.add(new CapturedRequest(form, parseQuery(exchange.getRequestURI().getRawQuery())));
        byte[] responseBody = responses.remove().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    private static void assertMove(CapturedRequest request,
                                  String sourcePath,
                                  String destinationDirectory,
                                  String filename) {
        assertEquals("filemanager", request.query().get("method"));
        assertEquals("move", request.query().get("opera"));
        JSONObject item = item(request);
        assertEquals(sourcePath, item.getString("path"));
        assertEquals(destinationDirectory, item.getString("dest"));
        assertEquals(filename, item.getString("newname"));
    }

    private static JSONObject item(CapturedRequest request) {
        return JSON.parseArray(request.form().get("filelist")).getJSONObject(0);
    }

    private static String conflict(String path) {
        return "{\"errno\":12,\"info\":[{\"errno\":-8,\"path\":\"" + path + "\"}]}";
    }

    private static String success() {
        return "{\"errno\":0,\"info\":[{\"errno\":0}]}";
    }

    private static Map<String, String> parseForm(String encoded) {
        return parseParameters(encoded);
    }

    private static Map<String, String> parseQuery(String encoded) {
        return parseParameters(encoded);
    }

    private static Map<String, String> parseParameters(String encoded) {
        Map<String, String> values = new HashMap<>();
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private record CapturedRequest(Map<String, String> form,
                                   Map<String, String> query) {
    }
}
