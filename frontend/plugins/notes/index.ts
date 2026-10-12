import {
  http,
  registerListHeader,
  registerNoteListFilter,
  registerPluginRoute,
  registerSidebarPanel,
  registerTopbarSearch,
  unregisterPlugin,
} from '@thisnode/plugin-api'
import GlobalSearch from './src/components/GlobalSearch.vue'
import TitleSearchBox from './src/components/TitleSearchBox.vue'
import NoteView from './src/views/NoteView.vue'
import NotesSidebarPanel from './src/components/NotesSidebarPanel.vue'
import { FILTER_ID, PLUGIN_ID, query, setMatchCount, setQuery } from './src/stores/titleSearch'

/**
 * 笔记域微应用的界面侧注册 —— 产物分离对齐后的「核心业务域」参考实现。
 *
 * <p>后端：plugin-notes（kind=CORE 的插件 jar，不可停用）；界面侧：本微应用。
 * 它登记本域的路由、侧栏主面板，以及两种搜索插槽：
 *
 * <ol>
 *   <li><b>插件路由</b>：/notes（列表）与 /notes/:id（笔记页面）；</li>
 *   <li><b>侧栏主面板</b>：新建/筛选/分组树/标签（原宿主 AppSidebar 的业务部分）；</li>
 *   <li><b>标题搜索</b>：列表页头按标题过滤；</li>
 *   <li><b>模糊搜索</b>：顶栏跨标题/摘要查询。两者随 notes 核心微应用一起装载。</li>
 * </ol>
 *
 * <p>favorite ★ 与 memory-curve 🧠 通过卡片工具/笔记工具/列表筛选插槽插进本域的视图 ——
 * 注册表在共享的 @thisnode/plugin-api 实例里（shallowReactive），装载顺序无关。
 *
 * <p>本模块是一个 **qiankun 微应用**：注册动作放进 mount()（宿主停用/重载时 unmount()
 * 经 unregisterPlugin 整体撤走）；独立运行（直接打开本 dev server）时 import 即注册。
 */
function register(): void {
  registerSidebarPanel({ pluginId: PLUGIN_ID, component: NotesSidebarPanel })
  registerListHeader({ pluginId: PLUGIN_ID, component: TitleSearchBox })
  registerNoteListFilter({
    pluginId: PLUGIN_ID,
    id: FILTER_ID,
    label: '标题搜索',
    icon: '🔍',
    hidden: true,
    load: async () => {
      const term = query.value.toLowerCase()
      if (!term) {
        setMatchCount(null)
        return []
      }
      const notes = await http.get<Array<{ id: string; title: string }>>('/api/notes', { limit: 2000 })
      const ids = notes.filter((note) => note.title.toLowerCase().includes(term)).map((note) => note.id)
      setMatchCount(ids.length)
      return ids
    },
  })
  registerTopbarSearch({ pluginId: PLUGIN_ID, component: GlobalSearch })
  registerPluginRoute({ pluginId: PLUGIN_ID, path: '/notes', name: 'notes', component: NoteView })
  registerPluginRoute({
    pluginId: PLUGIN_ID,
    path: '/notes/:id',
    name: 'note',
    component: NoteView,
  })
}

export async function bootstrap() {}

export async function mount() {
  register()
}

export async function unmount() {
  setQuery('')
  setMatchCount(null)
  unregisterPlugin(PLUGIN_ID)
}

if (!window.__POWERED_BY_QIANKUN__) {
  register()
  document.getElementById('plugin-root')?.replaceChildren(
    Object.assign(document.createElement('p'), {
      textContent: '笔记域独立预览：完整界面（列表、页面、侧栏面板）渲染在 thisnode 宿主里。',
    }),
  )
}
