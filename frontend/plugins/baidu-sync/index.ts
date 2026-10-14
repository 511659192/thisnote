import { registerPluginSettings, unregisterPlugin } from '@thisnode/plugin-api'
import BaiduSyncSettings from './src/views/BaiduSyncSettings.vue'

const PLUGIN_ID = 'baidu-sync'

export function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: BaiduSyncSettings })
}

export async function bootstrap() {}

export async function mount() {
  register()
}

export async function unmount() {
  unregisterPlugin(PLUGIN_ID)
}

if (!window.__POWERED_BY_QIANKUN__) {
  register()
  document.getElementById('plugin-root')?.replaceChildren(
    Object.assign(document.createElement('p'), {
      textContent: '百度网盘同步插件设置由 thisnote 宿主承载。',
    }),
  )
}
