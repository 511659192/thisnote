package com.thisnode.config;

import com.thisnode.plugin.PluginEndpointGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 平台侧的 Web 装配：CORS 与「停用插件端点闸门」。
 *
 * <p>按 sbp 最佳实践（参考 sbp-demo），主体是**纯平台**——应用路径配置随业务迁去了核心插件
 * （plugin-notes 的 NotesConfig），这里只保留平台真正需要的东西。允许的来源只含本机
 * （浏览器直接访问后端端口；开发期 Vite dev server 经代理同源）。后端只监听 127.0.0.1，
 * 宽松的 CORS 不扩大网络暴露面。
 */
@Configuration
public class ThisnodeConfig {

    /**
     * 插件状态与配置的 KV：**一个 JSON 文件**（{@code <data-root>/plugin-data.json}）。
     *
     * <p>它的前身是 {@code plugin_data} 表 —— 笔记数据文件化之后，这是平台里最后一张被
     * 真正读写的表，换成文件之后数据库里不再有任何被应用读写的表。文件路径默认与
     * 保险库同级（{@code ~/.thisnode/}），因为它不是笔记数据，不该混进保险库目录。
     */
    @Bean
    public com.thisnode.plugin.api.PluginDataStore pluginDataStore(
            @Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        return new com.thisnode.plugin.api.PluginDataStore(
                Paths.get(dataRoot, "plugin-data.json"));
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(PluginEndpointGate pluginGate) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
            }

            /** 停用的插件，其自有端点在这里被拦下；管理面（states/config/health/active）放行。 */
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(pluginGate).addPathPatterns("/api/plugins/**");
            }
        };
    }
}
