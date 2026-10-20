import { http } from '@thisnote/plugin-api'

export const stateResetApi = {
  /** 发出一次插件状态重置事件（活动插件清理并重建自己的状态）。 */
  reset: () => http.post<void>('/api/plugins/state-reset/reset'),
}
