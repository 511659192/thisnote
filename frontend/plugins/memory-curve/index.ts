import { reactive } from 'vue'
import { memoryCurveApi, type Grade, type MemoryCurveCardState } from './memoryCurveApi'
import {
  registerCardPlugin,
  registerNavEntry,
  registerPluginRoute,
  unregisterPlugin,
} from '@thisnode/plugin-api'
import MemoryCurveTool from './MemoryCurveTool.vue'

/**
 * 记忆曲线插件（参考 Anki 的 SM-2）。
 *
 * 状态保存在模块级 reactive map：按卡片 id 索引「是否开启 + 调度状态」。
 * 一个笔记页装载时由 NotePage 调 loadState 批量拉取；开关走通用插件状态面；
 * 评分走插件自己的复习端点，评分后用返回的最新调度覆盖本地。
 */
const state = reactive(new Map<string, MemoryCurveCardState>())

function blank(cardId: string): MemoryCurveCardState {
  return {
    cardId,
    enabled: false,
    schedule: {
      cardId: null, state: 'new', ease: 2.5, intervalDays: 0,
      dueAt: 0, lastReviewedAt: null, reps: 0, lapses: 0, due: false,
    },
  }
}

export const memoryCurve = {
  stateOf(cardId: string): MemoryCurveCardState {
    let s = state.get(cardId)
    if (!s) {
      s = blank(cardId)
      state.set(cardId, s)
    }
    return s
  },

  async loadState(cardIds: string[]): Promise<void> {
    if (!cardIds.length) return
    const cards = await memoryCurveApi.cards(cardIds)
    for (const c of cards) {
      state.set(c.cardId, c)
    }
    for (const id of cardIds) {
      if (!state.has(id)) state.set(id, blank(id))
    }
  },

  async toggle(cardId: string): Promise<void> {
    const current = this.stateOf(cardId)
    const next = !current.enabled
    await import('@thisnode/plugin-api').then((m) =>
      m.pluginApi.setState('memory-curve', 'card', cardId, next),
    )
    const updated = this.stateOf(cardId)
    updated.enabled = next
    if (next && !updated.schedule.cardId) {
      // 后端在启用钩子里建了"立即到期"的调度行；本地同步为该形态。
      updated.schedule = { ...updated.schedule, cardId, state: 'new', due: true, dueAt: Date.now() }
    }
  },

  async review(cardId: string, grade: Grade): Promise<void> {
    const r = await memoryCurveApi.review(cardId, grade)
    const updated = this.stateOf(cardId)
    updated.enabled = r.enabled
    updated.schedule = { ...r.schedule, due: r.schedule.dueAt <= Date.now() }
  },
}

/**
 * 本模块是一个 **qiankun 微应用**：注册动作在 `mount()` 里发生，`unmount()` 注销 ——
 * 宿主停用/重载插件时六个插槽的注册一起撤走，再启用时重新注册，无需刷新页面。
 * 独立运行（直接打开本 dev server）时 import 即注册，便于单独调试。
 */
function register(): void {
  registerCardPlugin({
    id: 'memory-curve',
    title: '记忆曲线',
    glyph: '🧠',
    loadState: (ids) => memoryCurve.loadState(ids),
    isEnabled: (cardId) => memoryCurve.stateOf(cardId).enabled,
    toggle: (cardId) => memoryCurve.toggle(cardId),
    cardComponent: MemoryCurveTool,
  })

  /**
   * 侧栏入口也由插件贡献：核心侧栏不认识「复习」这件事，它只渲染注册器里的入口，
   * 徽标数量（今天到期几张）由插件自己算。
   */
  registerNavEntry({
    pluginId: 'memory-curve',
    label: '复习',
    glyph: '🧠',
    to: '/review',
    badge: async () => (await memoryCurveApi.due(50)).length,
  })

  /** 复习页也由插件登记（核心路由表里不出现 /review 这个插件路径）。 */
  registerPluginRoute({
    pluginId: 'memory-curve',
    path: 'review',
    name: 'review',
    component: () => import('./ReviewView.vue'),
  })
}

export async function bootstrap() {}

export async function mount() {
  register()
}

export async function unmount() {
  unregisterPlugin('memory-curve')
}

if (!window.__POWERED_BY_QIANKUN__) {
  register()
  document.getElementById('plugin-root')?.replaceChildren(
    Object.assign(document.createElement('p'), {
      textContent: '记忆曲线插件独立预览：完整的界面（🧠 卡片徽标、复习页）渲染在 thisnode 宿主里。',
    }),
  )
}
