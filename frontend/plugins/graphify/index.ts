import {
  createPluginLifecycle,
  registerNavEntry,
  registerNoteListFilter,
  registerNoteTool,
  registerPluginRoute,
  registerPluginSettings,
  registerTopbarSearch,
} from '@thisnote/plugin-api'
import GraphifyNlpSearch from './src/components/GraphifyNlpSearch.vue'
import GraphifyNoteTool from './src/components/GraphifyNoteTool.vue'
import GraphifySettings from './src/components/GraphifySettings.vue'
import {
  GRAPHIFY_PLUGIN_ID,
  GRAPHIFY_TAG_FILTER_ID,
  activateTagFilter,
  loadFilteredNoteIds,
  selectedTag,
} from './src/tagFilter'

function graphTagFromLocation(): string {
  const hash = window.location.hash
  const queryStart = hash.indexOf('?')
  const query = queryStart >= 0 ? hash.slice(queryStart + 1) : window.location.search
  return new URLSearchParams(query).get('graphTag')?.trim() ?? ''
}

function register(): void {
  registerPluginSettings({ pluginId: GRAPHIFY_PLUGIN_ID, component: GraphifySettings })
  registerTopbarSearch({ pluginId: GRAPHIFY_PLUGIN_ID, component: GraphifyNlpSearch })
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
  const graphTag = graphTagFromLocation()
  if (graphTag) {
    selectedTag.value = graphTag
    activateTagFilter()
  }
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: GRAPHIFY_PLUGIN_ID,
  register,
  standaloneHint: 'Graphify 知识图谱插件：图谱、社区标签与索引设置都在 thisnote 宿主中使用。',
})
