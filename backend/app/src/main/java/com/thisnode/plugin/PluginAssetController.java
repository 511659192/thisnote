package com.thisnode.plugin;

import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.ObjectProvider;
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
import java.io.InputStream;
import java.util.Locale;

/**
 * 插件前端的静态产物：{@code /plugin-assets/<id>/<相对路径>}。
 *
 * <p>为什么不再用 {@code classpath:/plugin/} 的静态映射：插件现在是 plugins/ 目录里的**外置 jar**，
 * 它的资源不在应用的 classpath 上。这里经 PF4J 拿到那个插件的 {@code PluginClassLoader}，
 * 直接从它的 jar 里把文件读出来 —— 于是「换一个插件 jar = 换它的界面」在前端这一半也成立。
 *
 * <p>只读、且拒绝路径穿越；文件名带 hash 的那些（chunk-*.js）同样由这里端出。
 *
 * <p><b>只对 STARTED 的插件提供</b>——与 SBP 自己的 {@code PluginResourceResolver} 一致
 * （它也只扫 {@code PluginState.STARTED}）。否则"停用一个插件"在 HTTP 层面就等于没停：
 * 前端虽然不请求了，但产物仍然拿得到。
 */
@RestController
public class PluginAssetController {

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
        // 路径穿越与空路径都不放行
        if (relative.isEmpty() || relative.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        String resourcePath = "plugin/" + pluginId + "/" + relative;
        try (InputStream in = wrapper.getPluginClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(mediaType(relative))
                    // 插件产物文件名是固定的（plugin.js/plugin.css），宁可每次校验也不要缓存到旧版本
                    .cacheControl(CacheControl.noCache())
                    .body(new ByteArrayResource(bytes));
        }
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