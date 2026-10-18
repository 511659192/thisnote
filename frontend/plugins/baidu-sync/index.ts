import {
  createPluginLifecycle,
  registerNavEntry,
  registerPluginRoute,
  registerPluginSettings,
} from '@thisnote/plugin-api'
import BaiduSyncSettings from './src/views/BaiduSyncSettings.vue'

const PLUGIN_ID = 'baidu-sync'

function register(): void {
  // 设置面板仍供「插件管理 → 设置」内嵌使用；入口在侧栏「复习」之下（order 排序），
  // 插件停用随 unmount 注销，侧栏入口与路由一起消失。
  registerPluginSettings({ pluginId: PLUGIN_ID, component: BaiduSyncSettings })
  registerNavEntry({
    pluginId: PLUGIN_ID,
    label: '百度网盘同步',
    glyph: '☁️',
    to: '/baidu-sync',
    order: 2,
  })
  registerPluginRoute({
    pluginId: PLUGIN_ID,
    path: 'baidu-sync',
    name: 'baidu-sync',
    component: () => import('./src/views/BaiduSyncSettings.vue'),
  })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: '百度网盘同步插件独立预览：完整界面（网盘同步配置）渲染在 thisnote 宿主里。',
})
