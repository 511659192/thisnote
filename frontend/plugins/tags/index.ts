import {
  createPluginLifecycle,
  registerNavEntry,
  registerNoteListFilter,
  registerNoteTool,
  registerPluginRoute,
} from '@thisnote/plugin-api'
import TagsNoteTool from './src/components/TagsNoteTool.vue'
import {
  TAGS_FILTER_ID,
  TAGS_PLUGIN_ID,
  loadFilteredNoteIds,
} from './src/tagFilter'

function register(): void {
  registerNavEntry({
    pluginId: TAGS_PLUGIN_ID,
    label: '标签',
    glyph: '🏷',
    to: '/tags',
    order: 4,
  })
  registerPluginRoute({
    pluginId: TAGS_PLUGIN_ID,
    path: 'tags',
    name: 'tags',
    component: () => import('./src/views/TagsView.vue'),
  })
  registerNoteTool({
    pluginId: TAGS_PLUGIN_ID,
    title: '标签',
    glyph: '🏷',
    placement: 'below-title',
    order: 0,
    component: TagsNoteTool,
  })
  registerNoteListFilter({
    pluginId: TAGS_PLUGIN_ID,
    id: TAGS_FILTER_ID,
    label: '标签',
    icon: '🏷',
    hidden: true,
    load: loadFilteredNoteIds,
  })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: TAGS_PLUGIN_ID,
  register,
  standaloneHint: '标签插件独立预览：标签工具、标签页与筛选在 thisnote 宿主中使用。',
})
