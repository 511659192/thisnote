import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { noteApi } from '@/api/note'
import { bookApi } from '@/api'
import type { NoteDto, Book } from '@/api/types'
import { activeListFilter_, clearActiveListFilter as registryClearFilter, getNoteListFilter, toast } from '@thisnode/plugin-api'

/** Notes: list, selection, notebook navigation and title updates. */
export const useNoteStore = defineStore('notes', () => {

  const nodes = ref<NoteDto[]>([])
  const current = ref<NoteDto | null>(null)
  const loadingList = ref(false)
  const loadingNode = ref(false)
  const books = ref<Book[]>([])
  const bookCounts = ref<Record<string, number>>({})

  const activeBookId = ref<string | null>(null)
  const listQuery = ref('')
  const selectedIds = ref<string[]>([])

  let detailToken = 0

  const currentId = computed(() => current.value?.noteId ?? null)

  async function loadList() {
    loadingList.value = true
    try {
      nodes.value = await noteApi.list({
        bookId: activeBookId.value ?? undefined,
        text: listQuery.value || undefined,
      })
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      loadingList.value = false
    }
  }

  /**
   * 拉一次笔记本台账。
   *
   * <p><b>并发去重</b>：启动时 App 与侧栏几乎同时调它
   * （侧栏看到 notebooks 还空，于是又拉一次），不去重就是同一组请求打两遍。
   * 并发调用共用同一个 Promise；**不带新鲜期** —— 写操作之后的刷新必须实时。
   */
  let metaInflight: Promise<void> | null = null

  /**
   * 插件提供的列表筛选（例如「收藏」）。
   *
   * <p>它只是给核心这份列表**加一个条件**：插件回答"哪些 id 命中"，列表渲染、点开笔记、
   * 内容展示全由核心负责 —— 因此与「全部笔记/最近编辑」天然同构，不需要任何第二套布局。
   */
  /**
   * 插件列表筛选：激活指针在注册表里（跨微应用共享 —— 搜索框等插件 UI 也设置它），
   * 这里 watch 指针并调用筛选的 load() 取命中 id。null = 未激活（列表原样）。
   */
  const pluginFilter = computed(() => activeListFilter_())
  const pluginFilterIds = ref<Set<string> | null>(null)

  // 指针在注册表里（搜索框等任意微应用的 UI 都能设置）；这里 watch 它并调用筛选的 load()
  // 取命中 id。竞态防护：load 返回时指针若已再变，丢弃本次结果。
  watch(activeListFilter_, async (f) => {
    if (!f) {
      pluginFilterIds.value = null
      return
    }
    const filter = getNoteListFilter(f.filterId)
    if (!filter) {
      pluginFilterIds.value = null
      return
    }
    try {
      const ids = await filter.load()
      if (activeListFilter_() !== f) return
      pluginFilterIds.value = new Set(ids)
    } catch {
      pluginFilterIds.value = null
    }
  })

  function clearPluginFilter(): void {
    registryClearFilter()
  }

  /** 列表真正渲染的内容：没启用插件筛选时就是原样，启用时按 id 过滤。 */
  const notesView = computed(() => {
    const ids = pluginFilterIds.value
    if (!ids) return nodes.value
    return nodes.value.filter((n) => ids.has(n.noteId))
  })
  async function countNotesByBook(): Promise<Record<string, number>> {
    const counts: Record<string, number> = {}
    const pageSize = 500
    for (let offset = 0; ; offset += pageSize) {
      const page = await noteApi.list({ limit: pageSize, offset })
      for (const note of page) {
        if (note.bookId) counts[note.bookId] = (counts[note.bookId] ?? 0) + 1
      }
      if (page.length < pageSize) return counts
    }
  }

  async function refreshMeta(): Promise<void> {
    if (metaInflight) {
      return metaInflight
    }
    metaInflight = (async () => {
      try {
        const [loadedBooks, counts] = await Promise.all([bookApi.list(), countNotesByBook()])
        books.value = loadedBooks
        bookCounts.value = counts
      } catch (e) {
        /* meta refresh is best-effort */
      } finally {
        metaInflight = null
      }
    })()
    return metaInflight
  }

  async function refreshAll() {
    await Promise.all([loadList(), refreshMeta()])
  }

  /** 回到「全部笔记」：清掉分组限定，重新拉列表。 */
  function showAll() {
    activeBookId.value = null
    void loadList()
  }

  function setBook(id: string | null) {
    activeBookId.value = id
    void loadList()
  }

  // ---------------------------------------------------------------- 分组列表

  /** 侧栏可折叠的扁平分组列表。 */
  const groupsShown = ref(true)

  async function createGroup(title: string): Promise<Book> {
    const created = await bookApi.create(title)
    await refreshMeta()
    groupsShown.value = true
    return created
  }

  async function renameGroup(id: string, title: string) {
    await bookApi.update(id, { title })
    await refreshMeta()
  }

  async function deleteGroup(id: string) {
    await bookApi.remove(id)
    if (activeBookId.value === id) setBook(null)
    await refreshMeta()
  }

  function openGroup(id: string) {
    groupsShown.value = true
    setBook(id)
  }

  function setListQuery(q: string) {
    listQuery.value = q
    void loadList()
  }

  async function select(id: string | null) {
    if (!id) {
      current.value = null
      return
    }
    if (current.value?.noteId === id) return
    const token = ++detailToken
    loadingNode.value = true
    try {
      const detail = await noteApi.get(id)
      if (token !== detailToken) return
      current.value = detail
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      if (token === detailToken) loadingNode.value = false
    }
  }

  async function reloadCurrent() {
    if (!current.value) return
    const id = current.value.noteId
    current.value = await noteApi.get(id)
    await refreshMeta()
  }

  async function updateCurrent(changes: { title?: string }): Promise<NoteDto | null> {
    if (!current.value) return null
    const id = current.value.noteId
    try {
      const detail = await noteApi.update(id, changes)
      current.value = detail
      await Promise.all([loadList(), refreshMeta()])
      return detail
    } catch (e) {
      toast((e as Error).message, 'error')
      return null
    }
  }

  async function createNode(payload: {
    bookId?: string
  }): Promise<NoteDto | null> {
    try {
      const detail = await noteApi.create(payload)
      await Promise.all([loadList(), refreshMeta()])
      return detail
    } catch (e) {
      toast((e as Error).message, 'error')
      return null
    }
  }

  async function deleteForever(id: string) {
    await noteApi.delete(id)
    if (currentId.value === id) current.value = null
    await Promise.all([loadList(), refreshMeta()])
    toast('已彻底删除', 'success')
  }


  /** Re-read the current note from disk-backed state (after an external edit). */
  async function syncFromServer() {
    await loadList()
    if (currentId.value) {
      current.value = await noteApi.get(currentId.value)
    }
  }

  return {
    pluginFilter,
    pluginFilterIds,
    clearPluginFilter,
    notesView,
    nodes,
    current,
    currentId,
    loadingList,
    loadingNode,
    books,
    bookCounts,
    activeBookId,
    listQuery,
    selectedIds,
    loadList,
    refreshMeta,
    refreshAll,
    showAll,
    setBook,
    setListQuery,
    // 分组列表
    groupsShown,
    createGroup,
    renameGroup,
    deleteGroup,
    openGroup,
    select,
    reloadCurrent,
    updateCurrent,
    createNode,
    deleteForever,
    syncFromServer,
  }
})
