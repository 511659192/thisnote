import {
  registerListHeader,
  registerNoteListFilter,
  registerTopbarSearch,
  unregisterPlugin,
} from '@thisnode/plugin-api'
import GlobalSearch from './GlobalSearch.vue'
import SearchBox from './SearchBox.vue'
import { FILTER_ID, PLUGIN_ID, query, setMatchCount, setQuery } from './state'

/**
 * 标题搜索插件的前端注册 —— 服务插件的最小参考实现。
 *
 * <p>登记两类插槽：
 *
 * <ol>
 *   <li><b>列表页头部</b>：搜索框（输入即过滤）；</li>
 *   <li><b>列表筛选</b>：`hidden` 筛选 —— 不在侧栏渲染行，由搜索框在输入时激活；
 *       load() 拉全量笔记列表（后端 limit 默认 500，本地工具足够）按标题包含匹配，
 *       返回命中的 id，列表随之收窄。</li>
 * </ol>
 *
 * <p>没有后端端点：搜索在客户端对列表做标题包含匹配（本地工具，列表规模有限）。
 */
function register(): void {
  registerListHeader({ pluginId: PLUGIN_ID, component: SearchBox })
  registerTopbarSearch({ pluginId: PLUGIN_ID, component: GlobalSearch })
  registerNoteListFilter({
    pluginId: PLUGIN_ID,
    id: FILTER_ID,
    label: '标题搜索',
    icon: '🔍',
    hidden: true,
    load: async () => {
      const q = query.value.toLowerCase()
      if (!q) {
        setMatchCount(null)
        return []
      }
      const notes = await listTitles()
      const ids = notes.filter((n) => n.title.toLowerCase().includes(q)).map((n) => n.id)
      setMatchCount(ids.length)
      return ids
    },
  })
}

async function listTitles(): Promise<Array<{ id: string; title: string }>> {
  const { http } = await import('@thisnode/plugin-api')
  return http.get<Array<{ id: string; title: string }>>('/api/notes', { limit: 2000 })
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
      textContent: '标题搜索独立预览：搜索框渲染在 thisnode 宿主的列表页头部。',
    }),
  )
}
