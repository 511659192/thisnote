import { http } from '@thisnote/plugin-api'

export type PluginResetStatus = 'RESET' | 'SKIPPED' | 'FAILED'

export interface PluginResetResult {
  pluginId: string
  status: PluginResetStatus
  message: string
}

export interface PluginStateResetReport {
  ok: boolean
  results: PluginResetResult[]
}

export const stateResetApi = {
  /** 发出一次插件状态重置事件（各插件清理并重建自己的状态）。 */
  reset: () => http.post<PluginStateResetReport>('/api/plugins/state-reset/reset'),
}
