import { registerPluginSettings, unregisterPlugin } from '@thisnode/plugin-api'
import AttachmentImporterSettings from './src/views/AttachmentImporterSettings.vue'

const PLUGIN_ID = 'attachment-importer'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: AttachmentImporterSettings })
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
      textContent: '附件导入插件设置由 thisnote 宿主承载。',
    }),
  )
}
