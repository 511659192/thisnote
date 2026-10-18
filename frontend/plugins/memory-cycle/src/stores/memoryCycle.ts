import { reactive } from 'vue'
import { bumpPluginState } from '@thisnode/plugin-api'
import { memoryCycleApi, type Grade, type MemoryCycleCardState } from '../api/memoryCycleApi'

const state = reactive(new Map<string, MemoryCycleCardState>())
const stateVersions = new Map<string, number>()

function advanceStateVersion(cardId: string): number {
  const version = (stateVersions.get(cardId) ?? 0) + 1
  stateVersions.set(cardId, version)
  return version
}

function blank(cardId: string): MemoryCycleCardState {
  return {
    cardId,
    enabled: false,
    schedule: {
      cardId: null, state: 'new', level: 1, dueAt: 0,
      lastReviewedAt: null, reps: 0, lapses: 0, due: false,
    },
  }
}

export const memoryCycle = {
  stateOf(cardId: string): MemoryCycleCardState {
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
    const cards = await memoryCycleApi.cards(cardIds)
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
    const result = await memoryCycleApi.setEnabled(cardId, next)
    if (stateVersions.get(cardId) !== version) return
    bumpPluginState('memory-cycle')
    const updated = this.stateOf(cardId)
    updated.enabled = result.enabled
    if (result.enabled && !updated.schedule.cardId) {
      // 后端在启用钩子里建了"立即到期"的调度行；本地同步为该形态。
      updated.schedule = { ...updated.schedule, cardId, state: 'new', due: true, dueAt: Date.now() }
    } else if (!result.enabled) {
      const reset = blank(cardId)
      updated.schedule = reset.schedule
    }
  },

  async review(cardId: string, grade: Grade): Promise<MemoryCycleCardState> {
    const version = advanceStateVersion(cardId)
    const result = await memoryCycleApi.review(cardId, grade)
    const current = this.stateOf(cardId)
    const updated: MemoryCycleCardState = {
      ...current,
      ...result,
      schedule: { ...result.schedule, due: result.schedule.dueAt <= Date.now() },
    }
    if (stateVersions.get(cardId) === version) {
      Object.assign(current, updated)
    }
    return updated
  },
}
