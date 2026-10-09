import { registerPluginRoute, registerSidebarPanel, unregisterPlugin } from '@thisnode/plugin-api'
import NoteView from './src/views/NoteView.vue'
import NotesSidebarPanel from './src/components/NotesSidebarPanel.vue'

/**
 * 笔记域微应用的前端注册 —— 前后端拆分对齐后的「核心业务域」参考实现。
 *
 * <p>后端：plugin-notes（kind=CORE 的插件 jar，不可停用）；前端：本微应用。
 * 它登记两类插槽：
 *
 * <ol>
 *   <li><b>插件路由</b>：/notes（列表）与 /notes/:id（笔记页面）—— 原本静态写在宿主
 *       router 里，随域拆分改为登记制；</li>
 *   <li><b>侧栏主面板</b>：新建/筛选/分组树/标签（原宿主 AppSidebar 的业务部分）。</li>
 * </ol>
 *
 * <p>favorite ★ 与 memory-curve 🧠 通过卡片工具/笔记工具/列表筛选插槽插进本域的视图 ——
 * 注册表在共享的 @thisnode/plugin-api 实例里（shallowReactive），装载顺序无关。
 *
 * <p>本模块是一个 **qiankun 微应用**：注册动作放进 mount()（宿主停用/重载时 unmount()
 * 经 unregisterPlugin 整体撤走）；独立运行（直接打开本 dev server）时 import 即注册。
 */
const PLUGIN_ID = 'notes'

function register(): void {
  registerSidebarPanel({ pluginId: PLUGIN_ID, component: NotesSidebarPanel })
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
