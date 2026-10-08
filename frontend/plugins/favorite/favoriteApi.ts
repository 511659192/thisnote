import { http } from '@thisnode/plugin-api'
import { pluginApi } from '@thisnode/plugin-api'

/** 收藏插件自己的 API（开关本身走底座的通用 per-entity 面）。 */

export interface FavoriteNote {
  id: string
  title: string
  preview: string
}

export const FAVORITE_PLUGIN_ID = 'favorite'
export const ENTITY_NOTE = 'note'

export const favoriteApi = {
  /** 收藏了哪些笔记（插件端点：状态只有 id，标题与预览由插件回核心取）。 */
  notes: () => http.get<FavoriteNote[]>(`/api/plugins/${FAVORITE_PLUGIN_ID}/notes`),

  /** 某篇笔记是否被收藏。 */
  isFavorite: async (noteId: string): Promise<boolean> => {
    const map = await pluginApi.states(FAVORITE_PLUGIN_ID, ENTITY_NOTE, [noteId])
    return map[noteId] === true
  },

  /** 收藏 / 取消收藏。 */
  setFavorite: (noteId: string, value: boolean) =>
    pluginApi.setState(FAVORITE_PLUGIN_ID, ENTITY_NOTE, noteId, value),
}
