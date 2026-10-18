import { reactive } from 'vue'
import { emitPluginStateChange, registerNoteBatchAction, registerNoteRowAction, type NoteRef } from '@thisnode/plugin-api'
import { favoriteApi, FAVORITE_PLUGIN_ID } from './api/favoriteApi'

/**
 * 收藏在笔记列表里的入口（行内 ★ 与批量「收藏 + / −」）。
 *
 * <p>状态与读写都留在插件里：核心只负责把笔记引用递进注册的回调。
 */
/** 收藏状态（插件内的共享快照：列表行内图标、批量动作、笔记工具共用）。 */
export const favoriteIds = reactive(new Set<string>())

async function load(): Promise<void> {
  try {
    const rows = await favoriteApi.notes()
    favoriteIds.clear()
    for (const row of rows) favoriteIds.add(row.id)
  } catch {
    // 插件不可用时不影响列表
  }
}

export async function setFavorite(noteId: string, value: boolean): Promise<void> {
  await favoriteApi.setFavorite(noteId, value)
  if (value) favoriteIds.add(noteId)
  else favoriteIds.delete(noteId)
  emitPluginStateChange({ pluginId: FAVORITE_PLUGIN_ID, noteIds: [noteId] })
}

async function setMany(notes: NoteRef[], value: boolean): Promise<void> {
  const noteIds = notes.map((note) => note.noteId)
  if (!noteIds.length) return
  await favoriteApi.setFavorites(noteIds, value)
  for (const noteId of noteIds) {
    if (value) favoriteIds.add(noteId)
    else favoriteIds.delete(noteId)
  }
  emitPluginStateChange({ pluginId: FAVORITE_PLUGIN_ID, noteIds })
}

export function registerFavoriteActions(): void {
  registerNoteRowAction({
    pluginId: FAVORITE_PLUGIN_ID,
    glyph: '☆',
    activeGlyph: '★',
    title: (note) => (favoriteIds.has(note.noteId) ? '取消收藏' : '收藏'),
    isActive: (note) => favoriteIds.has(note.noteId),
    loadState: load,
    toggle: (note) => setFavorite(note.noteId, !favoriteIds.has(note.noteId)),
  })
  registerNoteBatchAction({
    pluginId: FAVORITE_PLUGIN_ID,
    label: '收藏 +',
    title: '开启所选笔记的收藏',
    run: (notes) => setMany(notes, true),
  })
  registerNoteBatchAction({
    pluginId: FAVORITE_PLUGIN_ID,
    label: '收藏 −',
    title: '关闭所选笔记的收藏',
    run: (notes) => setMany(notes, false),
  })
}
