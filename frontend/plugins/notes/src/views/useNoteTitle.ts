import { nextTick, ref, watch, type ComputedRef, type Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useNoteStore } from '@/stores/notes'
import { usePageStore } from '@/stores/notePage'
import { toast } from '@thisnote/plugin-api'
import type { NoteDto } from '@/api/types'

interface PageHandle {
  finishEditing: (cardId?: string) => Promise<void>
}

type NoteStore = ReturnType<typeof useNoteStore>
type PageStore = ReturnType<typeof usePageStore>

export function useNoteTitle(
  nodes: NoteStore,
  page: PageStore,
  pageRef: Ref<PageHandle | null>,
  allowNoteManagement: ComputedRef<boolean>,
) {
  const route = useRoute()
  const router = useRouter()
  const renaming = ref(false)
  const titleDraft = ref('')
  let titleDraftNoteId: string | null = null
  const titleInput = ref<HTMLInputElement | null>(null)
  const titlePromptOpen = ref(false)
  const titlePromptDraft = ref('')
  const titlePromptInput = ref<HTMLInputElement | null>(null)
  const titlePromptSaveButton = ref<HTMLButtonElement | null>(null)
  const titlePromptBusy = ref(false)
  let titlePromptNoteId: string | null = null
  let titlePromptPromise: Promise<boolean> | null = null
  let resolveTitlePrompt: ((canLeave: boolean) => void) | null = null
  let renameCancelled = false
  let pendingTitleCommit: Promise<NoteDto | null> | null = null

  async function saveTitle(title: string): Promise<NoteDto | null> {
    const pending = nodes.updateCurrent({ title })
    pendingTitleCommit = pending
    try {
      return await pending
    } finally {
      if (pendingTitleCommit === pending) pendingTitleCommit = null
    }
  }

  function requestTitlePrompt(note: NoteDto): Promise<boolean> {
    if (titlePromptPromise) return titlePromptPromise
    titlePromptNoteId = note.noteId
    titlePromptDraft.value = titleDraftNoteId === note.noteId ? titleDraft.value : note.title
    titlePromptBusy.value = false
    titlePromptOpen.value = true
    const pending = new Promise<boolean>((resolve) => {
      resolveTitlePrompt = resolve
    })
    titlePromptPromise = pending
    void nextTick(() => titlePromptInput.value?.focus())
    return pending
  }

  function finishTitlePrompt(canLeave: boolean) {
    const resolve = resolveTitlePrompt
    resolveTitlePrompt = null
    titlePromptPromise = null
    titlePromptNoteId = null
    titlePromptOpen.value = false
    titlePromptBusy.value = false
    resolve?.(canLeave)
  }

  function dismissTitlePrompt() {
    if (!titlePromptBusy.value) finishTitlePrompt(false)
  }

  function onTitlePromptKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      dismissTitlePrompt()
      return
    }
    if (event.key !== 'Tab') return
    const first = titlePromptInput.value
    const last = titlePromptSaveButton.value
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }

  async function saveTitleAndLeave() {
    if (titlePromptBusy.value) return
    const title = titlePromptDraft.value.trim()
    if (!title) {
      toast('笔记标题不能为空', 'error')
      titlePromptInput.value?.focus()
      return
    }
    const noteId = titlePromptNoteId
    if (!noteId || nodes.current?.noteId !== noteId) {
      finishTitlePrompt(false)
      return
    }
    titlePromptBusy.value = true
    try {
      const updated = await saveTitle(title)
      if (!updated) return
      page.noteTitle = updated.title
      titleDraft.value = updated.title
      titleDraftNoteId = updated.noteId
      finishTitlePrompt(true)
    } finally {
      titlePromptBusy.value = false
    }
  }

  async function deleteUntitledNoteAndLeave() {
    if (titlePromptBusy.value) return
    const noteId = titlePromptNoteId
    const note = nodes.current
    if (!noteId || note?.noteId !== noteId || note.title.trim()) {
      finishTitlePrompt(false)
      return
    }
    titlePromptBusy.value = true
    try {
      await nodes.deleteForever(noteId)
      finishTitlePrompt(true)
    } catch (error) {
      toast(`删除无标题笔记失败：${(error as Error).message}`, 'error')
    } finally {
      titlePromptBusy.value = false
    }
  }

  async function ensureTitleBeforeLeaving(): Promise<boolean> {
    await pageRef.value?.finishEditing()
    if (pendingTitleCommit) await pendingTitleCommit
    if (!allowNoteManagement.value) return true
    const note = nodes.current
    if (!note || note.title.trim()) return true
    const title = titleDraftNoteId === note.noteId ? titleDraft.value.trim() : ''
    if (!title) return requestTitlePrompt(note)
    const updated = await saveTitle(title)
    if (!updated) return false
    page.noteTitle = updated.title
    titleDraft.value = updated.title
    titleDraftNoteId = updated.noteId
    return true
  }

  onBeforeRouteLeave(() => ensureTitleBeforeLeaving())
  onBeforeRouteUpdate((to, from) => to.params.id === from.params.id ? true : ensureTitleBeforeLeaving())

  async function applyRoute() {
    const id = route.params.id as string | undefined
    if (!id) {
      nodes.current = null
      page.noteId = null
      titleDraft.value = ''
      titleDraftNoteId = null
      return
    }
    if (nodes.current?.noteId === id && page.noteId === id) return
    await nodes.select(id)
    if (nodes.current) {
      await page.load(id, nodes.current.cardIds ?? [], nodes.current.title)
      titleDraft.value = nodes.current.title
      titleDraftNoteId = nodes.current.noteId
    }
  }

  watch(() => route.params.id, applyRoute)
  watch(() => route.name, (name) => {
    if (name === 'notes') void applyRoute()
  })
  watch(() => page.noteTitle, (title) => {
    const currentNote = nodes.current
    if (!title || !currentNote || currentNote.noteId !== page.noteId) return
    currentNote.title = title
    const row = nodes.nodes.find((note) => note.noteId === currentNote.noteId)
    if (row) row.title = title
  })

  async function startRename() {
    if (!allowNoteManagement.value) return
    titleDraft.value = nodes.current?.title ?? ''
    titleDraftNoteId = nodes.current?.noteId ?? null
    renameCancelled = false
    renaming.value = true
    await nextTick()
    titleInput.value?.focus()
    await nextTick()
    titleInput.value?.select()
  }

  function cancelRename() {
    renameCancelled = true
    renaming.value = false
  }

  async function commitTitle() {
    if (!renaming.value) return
    renaming.value = false
    if (!allowNoteManagement.value) {
      titleDraft.value = nodes.current?.title ?? ''
      titleDraftNoteId = nodes.current?.noteId ?? null
      return
    }
    if (renameCancelled) {
      renameCancelled = false
      titleDraft.value = nodes.current?.title ?? ''
      titleDraftNoteId = nodes.current?.noteId ?? null
      return
    }
    if (!nodes.current) return
    const next = titleDraft.value.trim()
    if (!next || next === nodes.current.title) return
    const detail = await saveTitle(next)
    if (detail) {
      page.noteTitle = detail.title
      titleDraft.value = detail.title
      titleDraftNoteId = detail.noteId
    }
  }

  return {
    renaming,
    titleDraft,
    titleInput,
    titlePromptOpen,
    titlePromptDraft,
    titlePromptInput,
    titlePromptSaveButton,
    titlePromptBusy,
    onTitlePromptKeydown,
    saveTitleAndLeave,
    deleteUntitledNoteAndLeave,
    dismissTitlePrompt,
    applyRoute,
    startRename,
    cancelRename,
    commitTitle,
  }
}
