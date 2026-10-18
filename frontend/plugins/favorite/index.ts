import { createPluginLifecycle, registerNoteListFilter, registerNoteTool } from '@thisnote/plugin-api'
import FavoriteTool from './src/components/FavoriteTool.vue'
import { favoriteApi, FAVORITE_PLUGIN_ID } from './src/api/favoriteApi'
import { registerFavoriteActions } from './src/actions'

/**
 * 收藏插件的界面侧注册 —— 「实体插件」的参考实现，只挂两个槽位：
 *
 * <ol>
 *   <li><b>笔记工具</b>：笔记标题旁的 ★ 开关；</li>
 *   <li><b>列表筛选</b>：侧栏「笔记」组里的「收藏」。</li>
 * </ol>
 *
 * <p>注意第二个：收藏**没有自己的页面与布局**。它只是给核心那份笔记列表加一个条件
 * （"哪些 id 命中"），列表渲染、点开笔记、内容展示全归核心 —— 因此它与「全部笔记/最近编辑」
 * 天然同构，不存在两套布局漂移的问题，插件侧也只有一行 `load()`。
 *
 * <p>核心那边没有一处提到「收藏」这两个字：没有侧栏筛选项、没有 ★ 图标、没有 favorite 接口、
 * 数据库里也没有那一列。停用插件，这两处入口一起消失。
 */
function register(): void {
  registerNoteTool({
    pluginId: FAVORITE_PLUGIN_ID,
    title: '收藏',
    glyph: '☆',
    component: FavoriteTool,
  })

  const favoritesFilter = {
    pluginId: FAVORITE_PLUGIN_ID,
    id: 'favorites',
    label: '收藏',
    icon: '★',
    hideNoteManagement: true,
    /** 核心据此过滤自己的列表：插件只回答"哪些笔记被收藏了"。 */
    load: async () => (await favoriteApi.notes()).map((note) => note.id),
  }
  registerNoteListFilter(favoritesFilter)
  registerFavoriteActions()
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: FAVORITE_PLUGIN_ID,
  register,
  standaloneHint: '收藏插件独立预览：完整的界面（★ 工具、列表筛选）渲染在 thisnote 宿主里。',
})
