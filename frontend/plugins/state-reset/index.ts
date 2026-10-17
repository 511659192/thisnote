import { registerPluginSettings, unregisterPlugin } from '@thisnode/plugin-api'
import StateResetSettings from './src/views/StateResetSettings.vue'

const PLUGIN_ID = 'state-reset'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: StateResetSettings })
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
      textContent: '状态重置插件设置由 thisnote 宿主承载。',
    }),
  )
}
