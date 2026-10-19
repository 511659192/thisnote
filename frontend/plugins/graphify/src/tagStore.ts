import { reactive } from 'vue'
import type { NoteRef } from '@thisnote/plugin-api'
import { graphifyApi, type GraphifyNoteTags } from './api/graphifyApi'
import { isMeaningfulTag } from './tagLabels'

export const noteTags = reactive<Record<string, GraphifyNoteTags>>({})

let loaded = false
let loading: Promise<void> | null = null

function normalizeTags(tags: Partial<GraphifyNoteTags> | undefined): GraphifyNoteTags {
  return {
    concept: Array.isArray(tags?.concept) ? tags.concept.filter(isMeaningfulTag) : [],
    community: Array.isArray(tags?.community) ? tags.community.filter(isMeaningfulTag) : [],
  }
}

export async function loadTagState(_notes?: NoteRef[], force = false): Promise<void> {
  if (loaded && !force) return
  if (loading) return loading
  loading = (async () => {
    const latest = await graphifyApi.tags()
    const normalized: Record<string, GraphifyNoteTags> = {}
    for (const [noteId, tags] of Object.entries(latest)) {
      normalized[noteId] = normalizeTags(tags)
    }
    for (const noteId of Object.keys(noteTags)) delete noteTags[noteId]
    Object.assign(noteTags, normalized)
    loaded = true
  })()
  try {
    await loading
  } finally {
    loading = null
  }
}
