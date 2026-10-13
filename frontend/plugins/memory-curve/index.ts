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
 * 一个笔记页装载时由 NotePage 调 loadState 批量拉取；开关与评分都走插件自己的端点，
 * 操作后用服务端返回或重新加载的状态更新本地。
 */
const state = reactive(new Map<string, MemoryCurveCardState>())
const stateVersions = new Map<string, number>()

function advanceStateVersion(cardId: string): number {
  const version = (stateVersions.get(cardId) ?? 0) + 1
  stateVersions.set(cardId, version)
  return version
}

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
    const current = state.get(cardId)
    if (current) return current
    state.set(cardId, blank(cardId))
    return state.get(cardId)!
  },

  async loadState(cardIds: string[]): Promise<void> {
    if (!cardIds.length) return
    const versions = new Map<string, number>()
    for (const id of cardIds) {
      versions.set(id, stateVersions.get(id) ?? 0)
    }
    const cards = await memoryCurveApi.cards(cardIds)
    for (const card of cards) {
      if ((stateVersions.get(card.cardId) ?? 0) !== versions.get(card.cardId)) continue
      state.set(card.cardId, card)
    }
    for (const id of cardIds) {
      if (!state.has(id) && (stateVersions.get(id) ?? 0) === versions.get(id)) {
        state.set(id, blank(id))
      }
    }
  },

  async toggle(cardId: string): Promise<void> {
    const current = this.stateOf(cardId)
    const next = !current.enabled
    const version = advanceStateVersion(cardId)
    const result = await memoryCurveApi.setEnabled(cardId, next)
    if (stateVersions.get(cardId) !== version) return
    const updated = this.stateOf(cardId)
    updated.enabled = result.enabled
    if (result.enabled && !updated.schedule.cardId) {
      // 后端在启用钩子里建了"立即到期"的调度行；本地同步为该形态。
      updated.schedule = { ...updated.schedule, cardId, state: 'new', due: true, dueAt: Date.now() }
    }
  },

  async review(cardId: string, grade: Grade): Promise<void> {
    const version = advanceStateVersion(cardId)
    const r = await memoryCurveApi.review(cardId, grade)
    if (stateVersions.get(cardId) !== version) return
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
export function register(): void {
  registerCardPlugin({
    id: 'memory-curve',
    title: '记忆周期',
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

if (import.meta.hot) {
  import.meta.hot.accept((next) => {
    next?.register()
  })
}

if (!window.__POWERED_BY_QIANKUN__) {
  register()
  document.getElementById('plugin-root')?.replaceChildren(
    Object.assign(document.createElement('p'), {
      textContent: '记忆曲线插件独立预览：完整的界面（🧠 卡片徽标、复习页）渲染在 thisnode 宿主里。',
    }),
  )
}
