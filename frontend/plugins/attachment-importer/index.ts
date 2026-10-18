import { createPluginLifecycle, registerPluginSettings } from '@thisnode/plugin-api'
import AttachmentImporterSettings from './src/views/AttachmentImporterSettings.vue'

const PLUGIN_ID = 'attachment-importer'

function register(): void {
  registerPluginSettings({ pluginId: PLUGIN_ID, component: AttachmentImporterSettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: '附件导入插件设置由 thisnote 宿主承载。',
})
