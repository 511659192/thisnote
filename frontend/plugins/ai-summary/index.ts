import {
  createPluginLifecycle,
  registerNoteTool,
  registerPluginSettings,
} from '@thisnote/plugin-api'
import AiSummaryNoteTool from './src/components/AiSummaryNoteTool.vue'
import AiSummarySettings from './src/components/AiSummarySettings.vue'

const PLUGIN_ID = 'ai-summary'

function register(): void {
  registerNoteTool({
    pluginId: PLUGIN_ID,
    title: 'AI 总结',
    glyph: '✦',
    placement: 'below-title',
    order: -1,
    component: AiSummaryNoteTool,
  })
  registerPluginSettings({ pluginId: PLUGIN_ID, component: AiSummarySettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: 'AI 总结插件独立预览：总结会在 thisnote 笔记标题下方展示。',
})
