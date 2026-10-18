import { memoryCycleApi } from './src/api/memoryCycleApi'
import { memoryCycle } from './src/stores/memoryCycle'
import { registerMemoryCycleActions } from './src/actions'
import {
  registerCardPlugin,
  registerNavEntry,
  registerPluginRoute,
  unregisterPlugin,
} from '@thisnode/plugin-api'
import MemoryCycleTool from './src/components/MemoryCycleTool.vue'

/**
 * 本模块是一个 **qiankun 微应用**：注册动作在 `mount()` 里发生，`unmount()` 注销 ——
 * 宿主停用/重载插件时六个插槽的注册一起撤走，再启用时重新注册，无需刷新页面。
 * 独立运行（直接打开本 dev server）时 import 即注册，便于单独调试。
 */
export function register(): void {
  registerCardPlugin({
    id: 'memory-cycle',
    title: '记忆周期',
    glyph: '🧠',
    loadState: (ids) => memoryCycle.loadState(ids),
    isEnabled: (cardId) => memoryCycle.stateOf(cardId).enabled,
    toggle: (cardId) => memoryCycle.toggle(cardId),
    cardComponent: MemoryCycleTool,
  })

  /**
   * 侧栏入口也由插件贡献：核心侧栏不认识「复习」这件事，它只渲染注册器里的入口，
   * 徽标数量（今天到期几张）由插件自己算。
   */
  registerNavEntry({
    pluginId: 'memory-cycle',
    label: '复习',
    glyph: '🧠',
    to: '/review',
    order: 1,
    badge: async () => (await memoryCycleApi.dueCount()).count,
  })

  /** 复习页也由插件登记（核心路由表里不出现 /review 这个插件路径）。 */
  registerPluginRoute({
    pluginId: 'memory-cycle',
    path: 'review',
    name: 'review',
    component: () => import('./src/views/ReviewView.vue'),
  })

  /** 笔记列表里的行内 🧠 与批量「记忆 + / −」。 */
  registerMemoryCycleActions()
}

export async function bootstrap() {}

export async function mount() {
  register()
}

export async function unmount() {
  unregisterPlugin('memory-cycle')
}

if (import.meta.hot) {
  import.meta.hot.accept((next) => {
    next?.register()
  })
}

if (!window.__POWERED_BY_QIANKUN__) {
  register()
  document.getElementById('plugin-root')?.replaceChildren(
    Object.assign(document.createElement('p'), {
      textContent: '记忆周期插件独立预览：完整的界面（🧠 卡片徽标、复习页）渲染在 thisnote 宿主里。',
    }),
  )
}
