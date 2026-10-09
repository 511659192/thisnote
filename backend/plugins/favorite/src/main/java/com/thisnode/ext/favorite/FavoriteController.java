package com.thisnode.ext.favorite;

import com.thisnode.plugin.api.PluginDataStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏插件自己的端点：{@code /api/plugins/favorite/…}。
 *
 * <p>只有读——「收藏了哪些笔记」的开关本身走底座的通用 per-entity 面
 * （{@code PUT /api/plugins/favorite/states/note/{id}?value=…}），插件不必自己写一遍。
 *
 * <p>端点只回 id 列表：本插件不读笔记数据（因此不依赖核心插件），标题与预览由调用方拼。
 */
@RestController
@RequestMapping("/api/plugins/" + FavoritePlugin.PLUGIN_ID)
public class FavoriteController {

    private final PluginDataStore store;

    public FavoriteController(PluginDataStore store) {
        this.store = store;
    }

    /**
     * 收藏了哪些笔记 —— **只给 id**。
     *
     * <p>标题与预览不在这里拼：本插件不读笔记数据（因此不依赖核心插件），调用方拿这份 id 列表
     * 与它已有的笔记列表做交集即可。
     */
    @GetMapping("/notes")
    public List<Map<String, Object>> notes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : store.enabledEntityIds(FavoritePlugin.PLUGIN_ID,
                FavoritePlugin.ENTITY_TYPE_NOTE)) {
            out.add(Map.of("id", id));
        }
        return out;
    }
}
