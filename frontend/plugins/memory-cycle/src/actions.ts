import { bumpPluginState, registerNoteBatchAction, registerNoteRowAction, type NoteRef } from '@thisnode/plugin-api'
import { memoryCycleApi } from './api/memoryCycleApi'
import { memoryCycle } from './stores/memoryCycle'
import MemoryCycleRowAction from './components/MemoryCycleRowAction.vue'

/**
 * 记忆曲线在笔记列表里的入口（行内 🧠 与批量「记忆 + / −」）。
 *
 * <p>卡片状态复用插件自己的 store（与笔记页卡片上的 🧠 徽标同一份），因此两边联动。
 */
const PLUGIN_ID = 'memory-cycle'
/** 一次最多查 100 张卡片，避免 GET 的 URL 过长。 */
const CHUNK = 100

function cardIds(note: NoteRef): string[] {
  return note.cardIds ?? []
}

async function loadStates(ids: string[]): Promise<void> {
  for (let i = 0; i < ids.length; i += CHUNK) {
    await memoryCycle.loadState(ids.slice(i, i + CHUNK))
  }
}

async function load(notes: NoteRef[]): Promise<void> {
  const ids = [...new Set(notes.flatMap((note) => cardIds(note)))]
  if (!ids.length) return
  await loadStates(ids)
}

async function apply(notes: NoteRef[], value: boolean): Promise<string> {
  const ids = [...new Set(notes.flatMap((note) => cardIds(note)))]
  if (!ids.length) return '所选笔记还没有卡片'
  await memoryCycleApi.setStates(ids, value)
  await loadStates(ids)
  bumpPluginState(PLUGIN_ID)
  return `已${value ? '开启' : '关闭'} ${ids.length} 张卡片的记忆曲线`
}

export function registerMemoryCycleActions(): void {
  registerNoteRowAction({
    pluginId: PLUGIN_ID,
    // 行内图标复用卡片工具的同一枚 🧠 图标与开关逻辑
    component: MemoryCycleRowAction,
    loadState: load,
  })
  registerNoteBatchAction({
    pluginId: PLUGIN_ID,
    label: '记忆 +',
    title: '开启所选笔记卡片的记忆曲线',
    run: (notes) => apply(notes, true),
  })
  registerNoteBatchAction({
    pluginId: PLUGIN_ID,
    label: '记忆 −',
    title: '关闭所选笔记卡片的记忆曲线',
    run: (notes) => apply(notes, false),
  })
}
