import type { Router } from 'vue-router'
import {
  pluginApi,
  refreshPluginAvailability,
  type PluginInfo,
} from '@thisnote/plugin-api'
import { loadPluginFrontend, unloadPluginFrontend } from './loader'

/**
 * 应用插件启停并同步界面生命周期。
 *
 * <p>设置页与使用指南共用这一条链路，避免两处对「后端已启用、界面未装卸」
 * 的处理出现漂移。
 */
export async function applyPluginActive(
  pluginId: string,
  next: boolean,
  router: Router,
): Promise<PluginInfo> {
  const updated = await pluginApi.setActive(pluginId, next)
  await refreshPluginAvailability(true)
  if (next) {
    await loadPluginFrontend(pluginId, router)
  } else {
    await unloadPluginFrontend(pluginId, router)
  }
  return updated
}
