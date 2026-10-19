import { http } from './http'

/**
 * 通用插件管理面：列表 / 全局启停 / 健康。
 *
 * <p>插件自己的业务 API 与类型放在各自的插件目录（`plugins/memoryCurveApi.ts`、
 * `plugins/baiduPanApi.ts`）—— 通用底座只认这套管理面，不认识任何具体插件。
 */

export interface PluginHealth {
  status: 'ok' | 'degraded' | 'error' | 'unknown'
  message: string
}

/** 插件管理视图（GET /api/plugins/get） */
export interface PluginInfo {
  id: string
  title: string
  description: string
  /** 不可停用/不可在线重载（承载主体业务领域的插件，如 notes） */
  undeactivatable: boolean
  /** PF4J 插件依赖；空数组表示无硬依赖 */
  dependencies?: string[]
  /** 正在运行的实例版本（来自插件 jar 的 plugin.properties） */
  version: string
  /** 全局启停；默认 true */
  active: boolean
  health: PluginHealth
  /** 磁盘上那个 jar 声明的版本；读不到（开发模式的目录形态）时不出现 */
  diskVersion?: string | null
  /** 磁盘上的版本与运行中的实例不一致 —— 换了 jar，还没重载 */
  reloadAvailable?: boolean
}

export const pluginApi = {
  list: () => http.get<PluginInfo[]>('/api/plugins/get'),
  setActive: (pluginId: string, value: boolean) =>
    http.post<PluginInfo>('/api/plugins/active', {}, { pluginId, value }),
  /** 重建插件的类加载器与容器，让磁盘上的新 jar 生效（SBP 的 reloadPlugins）。 */
  reload: (pluginId: string) =>
    http.post<PluginInfo>('/api/plugins/reload', {}, { pluginId }),
}
