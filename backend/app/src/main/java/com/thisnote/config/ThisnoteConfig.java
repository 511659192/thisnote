package com.thisnote.config;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.support.config.FastJsonConfig;
import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import com.thisnote.plugin.PluginEndpointHandlerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 平台侧的 Web 装配：CORS 与「停用插件端点闸门」。
 *
 * <p>按 sbp 最佳实践（参考 sbp-demo），主体是**纯平台**——应用路径配置随业务迁去了核心插件
 * （plugin-notes 的 NotesConfig），这里只保留平台真正需要的东西。允许的来源只含本机
 * （浏览器直接访问后端端口；开发期 Vite dev server 经代理同源）。后端只监听 127.0.0.1，
 * 宽松的 CORS 不扩大网络暴露面。
 */
@Configuration
public class ThisnoteConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(PluginEndpointHandlerInterceptor pluginGate) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
            }

            /** 停用的插件，其自有端点在这里被拦下；插件管理端点放行。 */
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(pluginGate).addPathPatterns("/api/plugins/**");
            }

            /**
             * HTTP 的 JSON 交给 fastjson2（后端统一 JSON 库），并把 Jackson 的转换器从链上摘掉
             * —— 不让两个库各写一半。其余转换器（String / byte[] / Resource …）原样留着，
             * 供非 JSON 响应使用。
             *
             * <p>两处与 Jackson 的旧行为对齐，换库不改响应形状：
             * <ul>
             *   <li><b>null</b>：此前 Jackson 被 {@code spring.jackson.default-property-inclusion=non_null}
             *       配成省略 null，fastjson2 默认也省略 —— 不用额外配。</li>
             *   <li><b>字段序</b>：fastjson2 默认按字段名字母序输出，Jackson 是声明序；
             *       关掉字母序开关，响应里的键序与以前一致（只影响 bean/record，Map 一直是插入序）。</li>
             * </ul>
             */
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                JSONFactory.setDefaultWriterAlphabetic(false);
                FastJsonConfig config = new FastJsonConfig();
                // Map 的非字符串键（如记忆周期的 gradePreview<1..4, 时间>）：fastjson2 默认写成 {1:…} 这种
                // **不带引号的裸键**（不是合法 JSON，前端 JSON.parse 直接报错），Jackson 一直是 "1"。
                config.setWriterFeatures(JSONWriter.Feature.WriteNonStringKeyAsString);
                FastJsonHttpMessageConverter fastjson = new FastJsonHttpMessageConverter();
                fastjson.setFastJsonConfig(config);
                fastjson.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON,
                        new MediaType("application", "*+json")));
                converters.add(0, fastjson);
            }
        };
    }
}
