import { http } from '@thisnote/plugin-api'

export const GRAPHIFY_PLUGIN_ID = 'graphify'
export const GRAPHIFY_TAG_FILTER_ID = 'graphify-tags'
const BASE = '/api/plugins/graphify'

export interface GraphifyStatus {
  running: boolean
  stage: string
  progress: number
  completedNotes: number
  totalNotes: number
  queuedNotes: number
  indexed: boolean
  graphAvailable: boolean
  graphHtmlAvailable: boolean
  lastCompletedAt: string | null
  warnings: string[]
  error: string | null
}

export interface GraphifySettings {
  aiProfileId: string
  provider: string
  model: string
  executablePath: string
  keyConfigured: boolean
  profiles: GraphifyAiProfile[]
  aiError: string
  profilesError: string
}

export interface GraphifyAiProfile {
  id: string
  label: string
  provider: string
  model: string
  keyConfigured: boolean
}

export interface GraphifySettingsUpdate {
  executablePath: string
  aiProfileId: string
}

export interface GraphifyNoteTags {
  concept: string[]
  community: string[]
}

export interface GraphifyRelatedNote {
  noteId: string
  title: string
  reasons: string[]
}

export interface GraphifyRelatedNotes {
  available: boolean
  notes: GraphifyRelatedNote[]
}

export interface GraphifyGraphNode {
  id?: string
  label?: string
  source_file?: string
  community_name?: string
}

export interface GraphifyGraphLink {
  source?: string | number | { id?: string | number }
  target?: string | number | { id?: string | number }
}

export interface GraphifyGraph {
  nodes?: GraphifyGraphNode[]
  links?: GraphifyGraphLink[]
}

export const graphifyApi = {
  settings: () => http.get<GraphifySettings>(BASE + '/settings'),
  saveSettings: (body: GraphifySettingsUpdate) =>
    http.post<GraphifySettings>(BASE + '/settings', body),
  status: () => http.get<GraphifyStatus>(BASE + '/status'),
  index: (force = false) => http.post<{ accepted: boolean; force: boolean }>(BASE + '/index', { force }),
  graph: () => http.get<GraphifyGraph>(BASE + '/graph'),
  graphHtmlUrl: () => http.url(BASE + '/graph.html'),
  tags: () => http.get<Record<string, GraphifyNoteTags>>(BASE + '/tags'),
  related: (noteId: string) => http.get<GraphifyRelatedNotes>(BASE + '/related', { noteId }),
  relabelCommunities: () => http.post<{ accepted: boolean }>(BASE + '/communities/relabel'),
}
