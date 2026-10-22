import {
  createPluginLifecycle,
  registerNavEntry,
  registerNoteListFilter,
  registerNoteTool,
  registerPluginRoute,
  registerPluginSettings,
} from '@thisnote/plugin-api'
import TagsNoteTool from './src/components/TagsNoteTool.vue'
import TagsSettings from './src/components/TagsSettings.vue'
import {
  TAGS_FILTER_ID,
  TAGS_PLUGIN_ID,
  activateTagFilter,
  loadFilteredNoteIds,
} from './src/tagFilter'

function tagFromLocation(): string {
  const hash = window.location.hash
  const queryStart = hash.indexOf('?')
  const query = queryStart >= 0 ? hash.slice(queryStart + 1) : window.location.search
  return new URLSearchParams(query).get('tag')?.trim() ?? ''
}

function register(): void {
  registerPluginSettings({ pluginId: TAGS_PLUGIN_ID, component: TagsSettings })
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
    placement: 'page-footer',
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
  const tag = tagFromLocation()
  if (tag) activateTagFilter(tag)
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: TAGS_PLUGIN_ID,
  register,
  standaloneHint: '标签插件独立预览：标签工具、标签页与筛选在 thisnote 宿主中使用。',
})
