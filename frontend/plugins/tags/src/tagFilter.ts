import {
  activeListFilter_,
  clearActiveListFilter,
  setActiveListFilter,
} from '@thisnote/plugin-api'
import { ref } from 'vue'
import { TAGS_FILTER_ID, TAGS_PLUGIN_ID } from './api/tagsApi'
import { loadTagState, noteTags } from './tagStore'

export { TAGS_FILTER_ID, TAGS_PLUGIN_ID }

export const selectedTag = ref('')

export async function loadFilteredNoteIds(): Promise<string[]> {
  await loadTagState()
  const tag = selectedTag.value
  if (!tag) return []
  return Object.entries(noteTags)
    .filter(([, tags]) => tags.includes(tag))
    .map(([noteId]) => noteId)
}

export function activateTagFilter(tag: string): void {
  selectedTag.value = tag
  setActiveListFilter(TAGS_PLUGIN_ID, TAGS_FILTER_ID)
}

export function clearTagFilter(): void {
  selectedTag.value = ''
  const active = activeListFilter_()
  if (active?.pluginId === TAGS_PLUGIN_ID && active.filterId === TAGS_FILTER_ID) {
    clearActiveListFilter()
  }
}

export function refreshActiveTagFilter(): void {
  const active = activeListFilter_()
  if (active?.pluginId !== TAGS_PLUGIN_ID || active.filterId !== TAGS_FILTER_ID) return
  if (selectedTag.value) {
    setActiveListFilter(TAGS_PLUGIN_ID, TAGS_FILTER_ID)
  } else {
    clearActiveListFilter()
  }
}
