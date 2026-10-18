import { createPluginLifecycle, registerPluginSettings } from '@thisnote/plugin-api'
import CardImporterSettings from './src/views/CardImporterSettings.vue'

const PLUGIN_ID = 'card-importer'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: CardImporterSettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: '卡片导入插件设置由 thisnote 宿主承载。',
})
