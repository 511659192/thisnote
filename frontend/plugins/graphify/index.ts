import {
  createPluginLifecycle,
  registerNavEntry,
  registerNoteListFilter,
  registerNoteTool,
  registerPluginRoute,
  registerPluginSettings,
} from '@thisnote/plugin-api'
import GraphifyNoteTool from './src/components/GraphifyNoteTool.vue'
import GraphifySettings from './src/components/GraphifySettings.vue'
import {
  GRAPHIFY_PLUGIN_ID,
  GRAPHIFY_TAG_FILTER_ID,
  loadFilteredNoteIds,
} from './src/tagFilter'

function register(): void {
  registerPluginSettings({ pluginId: GRAPHIFY_PLUGIN_ID, component: GraphifySettings })
  registerNavEntry({
    pluginId: GRAPHIFY_PLUGIN_ID,
    label: '知识图谱',
    glyph: '◉',
    to: '/graphify',
    order: 3,
  })
  registerPluginRoute({
    pluginId: GRAPHIFY_PLUGIN_ID,
    path: 'graphify',
    name: 'graphify',
    component: () => import('./src/views/GraphifyView.vue'),
  })
  registerNoteTool({
    pluginId: GRAPHIFY_PLUGIN_ID,
    title: '相关笔记',
    glyph: '🏷',
    placement: 'below-title',
    order: 10,
    component: GraphifyNoteTool,
  })
  registerNoteListFilter({
    pluginId: GRAPHIFY_PLUGIN_ID,
    id: GRAPHIFY_TAG_FILTER_ID,
    label: '图谱标签',
    icon: '🏷',
    hidden: true,
    load: loadFilteredNoteIds,
  })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: GRAPHIFY_PLUGIN_ID,
  register,
  standaloneHint: 'Graphify 知识图谱插件：图谱、社区标签与索引设置都在 thisnote 宿主中使用。',
})
