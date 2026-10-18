import { http } from '@thisnode/plugin-api'

/** 收藏插件自己的 API 与状态。 */

export interface FavoriteNote {
  id: string
}

export const FAVORITE_PLUGIN_ID = 'favorite'

export const favoriteApi = {
  /** 收藏了哪些笔记（插件端点：状态只有 id，标题与预览由插件回核心取）。 */
  notes: () => http.get<FavoriteNote[]>(`/api/plugins/${FAVORITE_PLUGIN_ID}/get-favorites`),

  /** 某篇笔记是否被收藏。 */
  isFavorite: async (noteId: string): Promise<boolean> => {
    const state = await http.get<{ favorite: boolean }>(
      `/api/plugins/${FAVORITE_PLUGIN_ID}/get-state`, { noteId },
    )
    return state.favorite
  },

  /** 收藏 / 取消收藏。 */
  setFavorite: (noteId: string, value: boolean) =>
    http.post<{ favorite: boolean }>(
      `/api/plugins/${FAVORITE_PLUGIN_ID}/update-state`, {}, { noteId, value },
    ),

  /** 批量收藏 / 取消收藏（按笔记）。 */
  setFavorites: (noteIds: string[], value: boolean) =>
    http.post<{ updated: number }>(
      `/api/plugins/${FAVORITE_PLUGIN_ID}/update-state-batch`, { noteIds, value },
    ),
}

/**
 * 全部笔记 id（分页）。
 *
 * <p>收藏记录可能残留已删除笔记的 id（例如删除时收藏插件正好停用），计数时用它排除。
 */
export async function listNoteIds(): Promise<string[]> {
  const out: string[] = []
  const limit = 500
  for (let offset = 0; ; offset += limit) {
    const page = await http.get<Array<{ noteId: string }>>('/api/notes/get', { limit, offset })
    for (const note of page) out.push(note.noteId)
    if (page.length < limit) break
  }
  return out
}
