package com.thisnode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 单体交付：后端直接把前端的静态产物端出来（产物由 Vite 落到 {@code classpath:/static/}）。
 *
 * <p><b>为什么不用 Boot 的默认资源映射</b>（{@code spring.web.resources.add-mappings: false}）：
 * SBP 的 {@code PluginResourceHandlerRegistrationCustomizer} 会往默认资源链上挂一个
 * {@code PluginResourceResolver}，那个 resolver 的 {@code @Autowired @Lazy PluginManager}
 * 取不到时回落到 {@code ApplicationContextProvider}，而后者是按**调用方类加载器**查上下文的
 * —— 主应用的上下文从未注册进去（只有各插件的，见 SBP 的 {@code SpringBootPlugin.start()}），
 * 于是它的 {@code pluginManager} 为 null。后果不是"插件资源取不到"，而是**任何**走该资源链的
 * 路径（静态资源、{@code /api} 未命中、{@code /error}）统一 500。
 *
 * <p>这里自己注册一条不带 resolver 链的映射，就把那条链整条绕开了。插件的静态资源不受影响：
 * 插件前端产物由 {@code PluginAssetController} 从插件 jar 里的 {@code /plugin-assets/**} 端出。
 *
 * <p>未命中的路径如实返回 404（{@code /api/**} 尤其不能兜底成 index.html，否则"接口不存在"
 * 会被一张页面伪装成 200）；只有目录形态的路径（含根路径）落到 {@code index.html}，供前端
 * hash 路由接手。
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 目录（含根路径）不是资源：交给 index.html。
                        //
                        // 不能用 requested.isFile() 判目录：打进 jar 之后 classpath 资源不是文件系统
                        // 里的文件，isFile() 恒为 false，那会把 js/css 也判成目录、全部兜底成 index.html，
                        // 浏览器于是收到 "module script, but the MIME type is text/html"。
                        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                            Resource index = location.createRelative("index.html");
                            return index.exists() && index.isReadable() ? index : null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        return requested.exists() && requested.isReadable() ? requested : null;
                    }
                });
    }
}
