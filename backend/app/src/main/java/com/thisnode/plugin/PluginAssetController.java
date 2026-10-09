package com.thisnode.plugin;

import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 插件前端的静态产物：{@code /plugin-assets/<id>/<相对路径>}。
 *
 * <p><b>前后端分离</b>：前端产物不再打进插件 jar —— 各插件模块用 vite 产出到自己的
 * {@code frontend-dist/}，Maven 打包时由 antrun 拷到 {@code plugins-dist/frontend/<id>/}
 * （磁盘目录，与应用工作目录相对，见 {@code spring.sbp.plugins-frontend-root}）。
 * 这里按磁盘文件流式端出；换一份前端产物 = 换那个目录里的文件，不再需要重打插件 jar。
 *
 * <p>插件本体（descriptor + class）仍在 jar 里、由 PF4J 装载 —— 本控制器只借注册表做
 * <b>启停闸门</b>：只对 STARTED 的插件提供产物，否则"停用一个插件"在 HTTP 层面就等于没停
 * （前端虽然不请求了，但产物仍然拿得到）。
 *
 * <p>只读、且拒绝路径穿越；文件名带 hash 的分片（chunk-*.js）同样由这里端出。
 */
@RestController
public class PluginAssetController {

    /** 插件前端产物的磁盘根（相对应用工作目录；与 jar 的 plugins-root 平行的前端交付区）。 */
    @Value("${spring.sbp.plugins-frontend-root:plugins-dist/frontend}")
    private Path frontendRoot;

    private final ObjectProvider<PluginManager> pluginManager;

    public PluginAssetController(ObjectProvider<PluginManager> pluginManager) {
        this.pluginManager = pluginManager;
    }

    @GetMapping("/plugin-assets/{pluginId}/**")
    public ResponseEntity<Resource> asset(@PathVariable String pluginId, HttpServletRequest request) throws IOException {
        PluginManager manager = pluginManager.getIfAvailable();
        if (manager == null) {
            return ResponseEntity.notFound().build();
        }
        // 注册表既是启停闸门，也是 pluginId 的白名单：未知 id（含恶意路径段）一律 404
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        if (wrapper == null || wrapper.getPluginState() != PluginState.STARTED) {
            return ResponseEntity.notFound().build();
        }
        String prefix = "/plugin-assets/" + pluginId + "/";
        String uri = request.getRequestURI();
        int at = uri.indexOf(prefix);
        if (at < 0) {
            return ResponseEntity.notFound().build();
        }
        String relative = uri.substring(at + prefix.length());
        // 路径穿越与空路径都不放行（URL 解码后的 .. 也到不了这里： Spring 已解码，再验一次 normalize 前缀）
        if (relative.isEmpty() || relative.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        Path base = frontendRoot.resolve(pluginId).normalize();
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(file);
        return ResponseEntity.ok()
                .contentType(mediaType(relative))
                // 插件产物文件名是固定的（plugin.js/plugin.css），宁可每次校验也不要缓存到旧版本
                .cacheControl(CacheControl.noCache())
                .body(new ByteArrayResource(bytes));
    }

    private static MediaType mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return MediaType.parseMediaType("text/javascript; charset=utf-8");
        }
        if (lower.endsWith(".css")) {
            return MediaType.parseMediaType("text/css; charset=utf-8");
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".woff2")) {
            return MediaType.parseMediaType("font/woff2");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
