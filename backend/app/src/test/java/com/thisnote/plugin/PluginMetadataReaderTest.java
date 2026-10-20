package com.thisnote.plugin;

import com.thisnote.plugin.api.meta.PluginMetadata;
import com.thisnote.plugin.api.meta.PluginMetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginWrapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginMetadataReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsStaticMetadataFromDevelopmentDirectory() throws Exception {
        Path resource = tempDir.resolve("classes").resolve(PluginMetadataReader.RESOURCE_PATH);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, """
                {
                  "title": "测试插件",
                  "description": "停用后仍可读取",
                  "undeactivatable": true
                }
                """, StandardCharsets.UTF_8);
        PluginWrapper wrapper = mock(PluginWrapper.class);
        when(wrapper.getPluginId()).thenReturn("test");
        when(wrapper.getPluginClassLoader()).thenReturn(null);
        when(wrapper.getPluginPath()).thenReturn(tempDir);

        PluginMetadata metadata = PluginMetadataReader.read(wrapper);

        assertThat(metadata.title()).isEqualTo("测试插件");
        assertThat(metadata.undeactivatable()).isTrue();
    }
}
