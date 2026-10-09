import { registerTopbarSearch, unregisterPlugin } from '@thisnode/plugin-api'
import GlobalSearch from './GlobalSearch.vue'

/**
 * 模糊搜索插件的界面侧注册 —— 纯界面侧服务插件（零端点、零状态）。
 *
 * <p>只登记一个插槽：**顶栏搜索**（logo 右侧），输入即跨标题/摘要模糊匹配，
 * 下拉结果可直接跳转到笔记。匹配与数据都在组件内完成（拉全量列表 + 客户端模糊匹配，
 * 短 TTL 缓存），后端不参与。
 */
const PLUGIN_ID = 'fuzzy-search'

function register(): void {
  registerTopbarSearch({ pluginId: PLUGIN_ID, component: GlobalSearch })
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
      textContent: '模糊搜索独立预览：查询框渲染在 thisnode 宿主的顶栏（logo 右侧）。',
    }),
  )
}
