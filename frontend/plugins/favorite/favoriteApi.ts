import { http } from '@thisnode/plugin-api'

/** 收藏插件自己的 API 与状态。 */

export interface FavoriteNote {
  id: string
}

export const FAVORITE_PLUGIN_ID = 'favorite'

export const favoriteApi = {
  /** 收藏了哪些笔记（插件端点：状态只有 id，标题与预览由插件回核心取）。 */
  notes: () => http.get<FavoriteNote[]>(`/api/plugins/${FAVORITE_PLUGIN_ID}/notes`),

  /** 某篇笔记是否被收藏。 */
  isFavorite: async (noteId: string): Promise<boolean> => {
    const state = await http.get<{ favorite: boolean }>(
      `/api/plugins/${FAVORITE_PLUGIN_ID}/state`, { noteId },
    )
    return state.favorite
  },

  /** 收藏 / 取消收藏。 */
  setFavorite: (noteId: string, value: boolean) =>
    http.post<{ favorite: boolean }>(
      `/api/plugins/${FAVORITE_PLUGIN_ID}/state`, {}, { noteId, value },
    ),
}
