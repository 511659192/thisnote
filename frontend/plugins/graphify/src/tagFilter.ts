import { activeListFilter_, setActiveListFilter } from '@thisnote/plugin-api'
import { ref } from 'vue'
import {
  GRAPHIFY_PLUGIN_ID,
  GRAPHIFY_TAG_FILTER_ID,
  type GraphifyGraph,
} from './api/graphifyApi'
import { isMeaningfulTag } from './tagLabels'
import { loadTagState, noteTags } from './tagStore'

export { GRAPHIFY_PLUGIN_ID, GRAPHIFY_TAG_FILTER_ID }
export const selectedTag = ref('all')

export interface GraphifyTagItem {
  label: string
  occurrences: number
  edges: number
}

export interface GraphifyTagStats {
  concept: GraphifyTagItem[]
  community: GraphifyTagItem[]
}

type MutableTagStat = GraphifyTagItem

interface NodeTagInfo {
  concept?: string
  community?: string
}

function incrementTag(map: Map<string, MutableTagStat>, label: string): void {
  const current = map.get(label)
  if (current) {
    current.occurrences++
    return
  }
  map.set(label, { label, occurrences: 1, edges: 0 })
}

function endpointId(value: unknown): string | null {
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  if (value && typeof value === 'object' && 'id' in value) {
    const id = (value as { id?: unknown }).id
    if (typeof id === 'string' || typeof id === 'number') return String(id)
  }
  return null
}

function sortByEdges(a: MutableTagStat, b: MutableTagStat): number {
  return b.edges - a.edges || b.occurrences - a.occurrences || a.label.localeCompare(b.label, 'zh-CN')
}

function toResult(map: Map<string, MutableTagStat>): GraphifyTagItem[] {
  return [...map.values()]
    .sort(sortByEdges)
    .map(({ label, occurrences, edges }) => ({ label, occurrences, edges }))
}

export function buildTagStats(graph: GraphifyGraph | null): GraphifyTagStats {
  const concepts = new Map<string, MutableTagStat>()
  const communities = new Map<string, MutableTagStat>()
  const nodeTags = new Map<string, NodeTagInfo>()

  for (const node of graph?.nodes ?? []) {
    if (!node.id || !node.source_file) continue
    const label = node.label?.trim() ?? ''
    const communityName = node.community_name?.trim() ?? ''
    const concept = isMeaningfulTag(label) ? label : ''
    const community = isMeaningfulTag(communityName) ? communityName : ''
    if (!concept && !community) continue
    nodeTags.set(node.id, {
      concept: concept || undefined,
      community: community || undefined,
    })
    if (concept) incrementTag(concepts, concept)
    if (community) incrementTag(communities, community)
  }

  for (const link of graph?.links ?? []) {
    const edgeConcepts = new Set<string>()
    const edgeCommunities = new Set<string>()
    for (const value of [link.source, link.target]) {
      const id = endpointId(value)
      const info = id ? nodeTags.get(id) : undefined
      if (info?.concept) edgeConcepts.add(info.concept)
      if (info?.community) edgeCommunities.add(info.community)
    }
    for (const concept of edgeConcepts) {
      const stat = concepts.get(concept)
      if (stat) stat.edges++
    }
    for (const community of edgeCommunities) {
      const stat = communities.get(community)
      if (stat) stat.edges++
    }
  }

  return {
    concept: toResult(concepts),
    community: toResult(communities),
  }
}

export async function loadFilteredNoteIds(): Promise<string[]> {
  await loadTagState(undefined, true)
  const [kind, ...parts] = selectedTag.value.split(':')
  const tag = parts.join(':')
  return Object.entries(noteTags)
    .filter(([, values]) => {
      if (selectedTag.value === 'all') {
        return values.concept.length > 0 || values.community.length > 0
      }
      if (kind === 'concept') return values.concept.includes(tag)
      return values.community.includes(tag)
    })
    .map(([noteId]) => noteId)
}

export function activateTagFilter(): void {
  setActiveListFilter(GRAPHIFY_PLUGIN_ID, GRAPHIFY_TAG_FILTER_ID)
}

export function refreshActiveTagFilter(): void {
  const active = activeListFilter_()
  if (active?.pluginId === GRAPHIFY_PLUGIN_ID && active.filterId === GRAPHIFY_TAG_FILTER_ID) {
    activateTagFilter()
  }
}
