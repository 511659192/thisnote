import { registerPluginSettings, unregisterPlugin } from '@thisnode/plugin-api'
import CardImporterSettings from './src/views/CardImporterSettings.vue'

const PLUGIN_ID = 'card-importer'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: CardImporterSettings })
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
      textContent: '卡片导入插件设置由 thisnote 宿主承载。',
    }),
  )
}
