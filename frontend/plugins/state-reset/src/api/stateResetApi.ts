import { http } from '@thisnode/plugin-api'

export const stateResetApi = {
  /** 发出一次插件状态重置事件（各插件清理并重建自己的状态）。 */
  reset: () => http.post<{ ok: boolean }>('/api/plugins/state-reset/reset'),
}
