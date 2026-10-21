import { createPluginLifecycle, registerNoteTool, registerPluginSettings } from '@thisnote/plugin-api'
import AiTitleNoteTool from './src/components/AiTitleNoteTool.vue'
import AiTitleSettings from './src/components/AiTitleSettings.vue'

const PLUGIN_ID = 'ai-title'

function register(): void {
  registerNoteTool({
    pluginId: PLUGIN_ID,
    title: 'AI 标题',
    glyph: '✦',
    component: AiTitleNoteTool,
  })
  registerPluginSettings({ pluginId: PLUGIN_ID, component: AiTitleSettings })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: 'AI 标题插件独立预览：在笔记标题右侧生成候选并替换标题。',
})
