import { defineStore } from 'pinia'
import { computed, reactive, ref, watch } from 'vue'
import { noteApi } from '@/api/note'
import { notebookApi } from '@/api'
import type { NoteDetail, NoteMeta, Notebook } from '@/api/types'
import { activeListFilter_, clearActiveListFilter as registryClearFilter, getNoteListFilter } from '@thisnode/plugin-api'
import { useUiStore } from './ui'

const AUTOSAVE_DEBOUNCE_MS = 500

/**
 * Notes: list, selection and autosave.
 *
 * Autosave is per-node debounced (F-P-01 防抖 500ms). A pending save is flushed when the
 * user switches notes so fast navigation never loses the last keystrokes.
 */
export const useNoteStore = defineStore('notes', () => {
  const ui = useUiStore()

  const nodes = ref<NoteMeta[]>([])
  const current = ref<NoteDetail | null>(null)
  const loadingList = ref(false)
  const loadingNode = ref(false)
  const saving = ref(false)
  const savedAt = ref<number | null>(null)
  const notebooks = ref<Notebook[]>([])

  const activeNotebookId = ref<string | null>(null)
  const listQuery = ref('')
  const selectedIds = ref<string[]>([])

  /** Per-node pending content so switching notes mid-debounce is lossless. */
  const pending = reactive<Record<string, { contentMd?: string; title?: string }>>({})
  const timers = new Map<string, number>()
  let detailToken = 0

  const currentId = computed(() => current.value?.id ?? null)
  const wordCount = computed(() => {
    const text = current.value?.contentMd ?? ''
    const cjk = (text.match(/[\u3400-\u9fff]/g) || []).length
    const latin = (text.replace(/[\u3400-\u9fff]/g, ' ').match(/[A-Za-z0-9_'-]+/g) || []).length
    return cjk + latin
  })

  async function loadList() {
    loadingList.value = true
    try {
      nodes.value = await noteApi.list({
        notebookId: activeNotebookId.value ?? undefined,
        text: listQuery.value || undefined,
      })
    } catch (e) {
      ui.toast((e as Error).message, 'error')
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
    return nodes.value.filter((n) => ids.has(n.id))
  })
  async function refreshMeta(): Promise<void> {
    if (metaInflight) {
      return metaInflight
    }
    metaInflight = (async () => {
      try {
        notebooks.value = await notebookApi.list()
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
    activeNotebookId.value = null
    void loadList()
  }

  function setNotebook(id: string | null) {
    activeNotebookId.value = id
    void loadList()
  }

  // ---------------------------------------------------------------- 分组树
  //
  // 父子关系本来就存在后端（notebooks.parentId + type=group/notebook），这里只是把它读成树：
  // 侧栏按 parentId 递归渲染，移动分组 = 改 parentId，笔记进分组 = 改笔记 meta 里的 notebookId。

  /** 展开的分组 id（ref<Set> 必须整体替换才会触发更新）。持久化到 localStorage：树的展开状态
      应该像文件管理器一样记住，不然每次刷新都要重新一层层点开。 */
  const EXPANDED_KEY = 'thisnode.groups.expanded'
  function loadExpanded(): Set<string> {
    try {
      const raw = localStorage.getItem(EXPANDED_KEY)
      return new Set<string>(raw ? (JSON.parse(raw) as string[]) : [])
    } catch {
      return new Set()
    }
  }
  function saveExpanded(next: Set<string>) {
    expandedGroups.value = next
    try {
      localStorage.setItem(EXPANDED_KEY, JSON.stringify([...next]))
    } catch {
      // 存不进去也不影响本次会话
    }
  }

  const expandedGroups = ref<Set<string>>(loadExpanded())
  /** 「全部笔记」那一层的展开态：收起时侧栏只看核心视图，不显示分组树。 */
  const groupsShown = ref(true)

  const sortGroups = (a: Notebook, b: Notebook) =>
    (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.title.localeCompare(b.title, 'zh')

  /** 顶层分组（没有父级的）。 */
  const rootGroups = computed(() => notebooks.value.filter((g) => !g.parentId).sort(sortGroups))

  /** 某个分组的直接子分组。 */
  function childGroups(parentId: string): Notebook[] {
    return notebooks.value.filter((g) => g.parentId === parentId).sort(sortGroups)
  }

  function hasChildren(id: string): boolean {
    return notebooks.value.some((g) => g.parentId === id)
  }

  /**
   * 按当前展开状态把树压平成一行行（带层级深度）。
   *
   * <p>侧栏用左内边距表现层级，就不用写递归组件了：树的深度靠数据表达，渲染保持一层循环。
   */
  const visibleGroups = computed(() => {
    const out: { group: Notebook; depth: number }[] = []
    const walk = (items: Notebook[], depth: number) => {
      for (const group of items) {
        out.push({ group, depth })
        if (expandedGroups.value.has(group.id)) walk(childGroups(group.id), depth + 1)
      }
    }
    walk(rootGroups.value, 0)
    return out
  })

  function isGroupExpanded(id: string): boolean {
    return expandedGroups.value.has(id)
  }

  function toggleGroup(id: string) {
    const next = new Set(expandedGroups.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    saveExpanded(next)
  }

  function expandGroup(id: string) {
    if (expandedGroups.value.has(id)) return
    saveExpanded(new Set([...expandedGroups.value, id]))
  }

  /** 把 groupId 的祖先链收集出来 —— 定位到某个分组时要用它逐级展开。 */
  function ancestorsOf(id: string): string[] {
    const out: string[] = []
    let cursor = notebooks.value.find((g) => g.id === id)?.parentId ?? null
    while (cursor) {
      out.push(cursor)
      cursor = notebooks.value.find((g) => g.id === cursor)?.parentId ?? null
    }
    return out
  }

  /** candidate 是不是 groupId 的后代（移动分组时用来挡「移到自己里面」）。 */
  function isDescendant(candidate: string, groupId: string): boolean {
    let cursor: string | null = candidate
    while (cursor) {
      if (cursor === groupId) return true
      cursor = notebooks.value.find((g) => g.id === cursor)?.parentId ?? null
    }
    return false
  }

  async function createGroup(title: string): Promise<Notebook> {
    const created = await notebookApi.create(title)
    await refreshMeta()
    groupsShown.value = true
    return created
  }

  /**
   * update 是全量替换（parentId=null 就是移到顶层）：四个字段都要从当前台账值出发带上，
   * 缺一个就等于显式清掉那个字段。
   */
  async function patchGroup(
    id: string,
    patch: { title?: string; parentId?: string | null },
  ) {
    const current = notebooks.value.find((n) => n.id === id)
    await notebookApi.update(id, {
      title: patch.title ?? current?.title ?? '',
      parentId: patch.parentId !== undefined ? patch.parentId : (current?.parentId ?? null),
      icon: current?.icon ?? null,
      sortOrder: current?.sortOrder ?? null,
    })
  }

  async function renameGroup(id: string, title: string) {
    await patchGroup(id, { title })
    await refreshMeta()
  }

  async function deleteGroup(id: string) {
    await notebookApi.remove(id)
    if (activeNotebookId.value === id) setNotebook(null)
    await refreshMeta()
  }

  async function moveGroup(id: string, parentId: string | null) {
    if (parentId && (parentId === id || isDescendant(parentId, id))) {
      throw new Error('不能把分组移到它自己或它的子分组里')
    }
    await patchGroup(id, { parentId })
    if (parentId) expandGroup(parentId)
    await refreshMeta()
  }

  /** 把一篇笔记移到某个分组（单归属：会离开原来的分组）。 */
  async function moveNoteToGroup(noteId: string, notebookId: string) {
    await notebookApi.addNode(notebookId, noteId)
    expandGroup(notebookId)
    await Promise.all([loadList(), refreshMeta()])
  }

  /** 打开某个分组：展开它的祖先链，并在右侧列表按它过滤。 */
  function openGroup(id: string) {
    for (const ancestor of ancestorsOf(id)) expandGroup(ancestor)
    setNotebook(id)
  }

  function setListQuery(q: string) {
    listQuery.value = q
    void loadList()
  }

  async function select(id: string | null) {
    await flushAll()
    if (!id) {
      current.value = null
      return
    }
    if (current.value?.id === id) return
    const token = ++detailToken
    loadingNode.value = true
    try {
      const detail = await noteApi.get(id)
      if (token !== detailToken) return
      current.value = detail
      savedAt.value = null
    } catch (e) {
      ui.toast((e as Error).message, 'error')
    } finally {
      if (token === detailToken) loadingNode.value = false
    }
  }

  async function reloadCurrent() {
    if (!current.value) return
    const id = current.value.id
    current.value = await noteApi.get(id)
    await refreshMeta()
  }

  /** Update the open note and keep the cached detail in sync. */
  async function updateCurrent(changes: {
    title?: string
    notebookId?: string
  }): Promise<NoteDetail | null> {
    if (!current.value) return null
    const id = current.value.id
    try {
      const detail = await noteApi.update(id, changes)
      current.value = detail
      await Promise.all([loadList(), refreshMeta()])
      return detail
    } catch (e) {
      ui.toast((e as Error).message, 'error')
      return null
    }
  }

  /** Apply a content/title edit locally and schedule a debounced save. */
  function edit(changes: { contentMd?: string; title?: string }) {
    const doc = current.value
    if (!doc) return
    if (changes.contentMd !== undefined) doc.contentMd = changes.contentMd
    if (changes.title !== undefined) doc.title = changes.title

    const bucket = pending[doc.id] ?? {}
    Object.assign(bucket, changes)
    pending[doc.id] = bucket

    const existing = timers.get(doc.id)
    if (existing) window.clearTimeout(existing)
    timers.set(
      doc.id,
      window.setTimeout(() => void flush(doc.id), AUTOSAVE_DEBOUNCE_MS),
    )
  }

  /** Immediately persist any debounced edit for one node. */
  async function flush(id: string) {
    const bucket = pending[id]
    if (!bucket) return
    delete pending[id]
    const timer = timers.get(id)
    if (timer) {
      window.clearTimeout(timer)
      timers.delete(id)
    }
    saving.value = true
    try {
      const detail = await noteApi.update(id, bucket)
      if (current.value?.id === id) {
        // Keep local cursor/selection state authoritative: only merge server fields.
        current.value.updatedAt = detail.updatedAt
      }
      savedAt.value = Date.now()
      scheduleListRefresh()
    } catch (e) {
      ui.toast(`保存失败：${(e as Error).message}`, 'error')
    } finally {
      saving.value = false
    }
  }

  async function flushAll() {
    await Promise.all(Object.keys(pending).map((id) => flush(id)))
  }

  let listRefreshTimer: number | null = null
  function scheduleListRefresh() {
    if (listRefreshTimer) window.clearTimeout(listRefreshTimer)
    listRefreshTimer = window.setTimeout(() => void loadList(), 900)
  }

  async function createNode(payload: {
    title?: string
    contentMd?: string
    type?: string
    notebookId?: string
  }): Promise<NoteDetail | null> {
    try {
      const detail = await noteApi.create(payload)
      await Promise.all([loadList(), refreshMeta()])
      return detail
    } catch (e) {
      ui.toast((e as Error).message, 'error')
      return null
    }
  }

  async function createAndSelect(payload: { title?: string; contentMd?: string; type?: string }) {
    // 「新建时自动带入当前分组」：正看着某个分组时新建，笔记就落在那儿；
    // 在顶层（全部笔记/最近编辑）新建则不带，由后端放进默认分组。
    const detail = await createNode({ ...payload, notebookId: activeNotebookId.value ?? undefined })
    if (detail) {
      await select(detail.id)
    }
    return detail
  }

  async function deleteForever(id: string) {
    await noteApi.delete(id)
    if (currentId.value === id) current.value = null
    await Promise.all([loadList(), refreshMeta()])
    ui.toast('已彻底删除', 'success')
  }


  /** Re-read the current note from disk-backed state (after an external edit). */
  async function syncFromServer() {
    await loadList()
    if (currentId.value) {
      const id = currentId.value
      const stale = pending[id]
      if (!stale) {
        current.value = await noteApi.get(id)
      }
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
    saving,
    savedAt,
    notebooks,
    activeNotebookId,
    listQuery,
    selectedIds,
    wordCount,
    loadList,
    refreshMeta,
    refreshAll,
    showAll,
    setNotebook,
    setListQuery,
    // 分组树
    expandedGroups,
    groupsShown,
    rootGroups,
    childGroups,
    hasChildren,
    visibleGroups,
    isGroupExpanded,
    toggleGroup,
    expandGroup,
    createGroup,
    renameGroup,
    deleteGroup,
    moveGroup,
    moveNoteToGroup,
    openGroup,
    select,
    reloadCurrent,
    updateCurrent,
    edit,
    flush,
    flushAll,
    createNode,
    createAndSelect,
    deleteForever,
    syncFromServer,
  }
})
