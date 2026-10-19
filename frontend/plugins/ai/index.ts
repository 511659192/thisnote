import { createPluginLifecycle, registerPluginSettings } from '@thisnote/plugin-api'
import AiSettings from './src/components/AiSettings.vue'

const PLUGIN_ID = 'ai'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: AiSettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: 'AI 能力插件独立预览：Provider 设置渲染在 thisnote 插件管理中。',
})
