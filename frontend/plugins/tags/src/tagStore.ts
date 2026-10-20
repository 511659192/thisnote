import { reactive } from 'vue'
import { tagsApi, type TagsByNote } from './api/tagsApi'

export const noteTags = reactive<TagsByNote>({})

let loaded = false
let loading: Promise<void> | null = null

export interface TagUsage {
  label: string
  count: number
  noteCount: number
}

export const TAG_DISPLAY_LIMIT = 100

function normalizeTags(tags: unknown): string[] {
  if (!Array.isArray(tags)) return []
  const values = tags
    .filter((tag): tag is string => typeof tag === 'string' && Boolean(tag.trim()))
    .map((tag) => tag.trim())
  return [...new Set(values)]
}

export async function loadTagState(force = false): Promise<void> {
  if (loaded && !force) return
  if (loading) return loading
  loading = (async () => {
    const latest = await tagsApi.tags()
    const normalized: TagsByNote = {}
    for (const [noteId, tags] of Object.entries(latest)) {
      const values = normalizeTags(tags)
      if (values.length) normalized[noteId] = values
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

export function updateNoteTags(noteId: string, tags: string[]): void {
  const values = normalizeTags(tags)
  if (values.length) {
    noteTags[noteId] = values
  } else {
    delete noteTags[noteId]
  }
}

export function buildTagUsage(
  tagsByNote: TagsByNote,
  occurrenceCounts: Record<string, number> = {},
): TagUsage[] {
  const noteCounts = new Map<string, number>()
  for (const tags of Object.values(tagsByNote)) {
    for (const tag of tags) {
      noteCounts.set(tag, (noteCounts.get(tag) ?? 0) + 1)
    }
  }
  return [...noteCounts.entries()]
    .map(([label, noteCount]) => ({
      label,
      count: occurrenceCounts[label] ?? 0,
      noteCount,
    }))
    .sort((a, b) => b.count - a.count
      || b.noteCount - a.noteCount
      || a.label.localeCompare(b.label, 'zh-CN'))
}
