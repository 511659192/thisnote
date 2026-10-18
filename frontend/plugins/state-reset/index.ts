import { createPluginLifecycle, registerPluginSettings } from '@thisnode/plugin-api'
import StateResetSettings from './src/views/StateResetSettings.vue'

const PLUGIN_ID = 'state-reset'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: StateResetSettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: '状态重置插件设置由 thisnote 宿主承载。',
})
