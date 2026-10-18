<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { clearActiveListFilter, setActiveListFilter } from '@thisnode/plugin-api'
import { useNoteStore } from '@/stores/notes'
import { FILTER_ID, PLUGIN_ID, matchCount, query as queryState, setQuery } from '../stores/titleSearch'

/**
 * notes 核心的标题搜索框（列表页头部插槽 UI）。
 *
 * <p>输入即激活 notes 自己登记的列表筛选（防抖 250ms）—— 筛选的 load() 在 notes 域的
 * watch 里被调用，按标题包含匹配返回命中的 id，列表随之收窄；字段右侧实时显示
 * 「命中 N 篇」，Esc 或清空按钮撤掉筛选并复位。
 */
const query = queryState
const inputEl = ref<HTMLInputElement | null>(null)
const nodes = useNoteStore()
const router = useRouter()
let timer: number | null = null

watch(query, (q) => {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    const trimmed = q.trim()
    setQuery(trimmed)
    if (trimmed) {
      setActiveListFilter(PLUGIN_ID, FILTER_ID)
    } else {
      clearActiveListFilter()
    }
  }, 250)
})

async function createNote() {
  if (router.currentRoute.value.path !== '/notes') {
    const failure = await router.push('/notes')
    if (failure) return
  }
  const detail = await nodes.createNode({ bookId: nodes.activeBookId ?? undefined })
  if (detail) await router.push(`/notes/${detail.noteId}`)
}

function toggleBatch() {
  if (nodes.batchSelecting) nodes.stopBatchSelect()
  else nodes.startBatchSelect()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    query.value = ''
    inputEl.value?.blur()
  }
}
</script>

<template>
  <div class="title-search-row">
    <div class="title-search" :class="{ filled: !!query }">
      <span class="ico">🔍</span>
      <input
        ref="inputEl"
        v-model="query"
        class="input"
        type="text"
        placeholder="按标题搜索…"
        spellcheck="false"
        @keydown.esc="onKeydown"
      />
      <span v-if="query && matchCount !== null" class="count">{{ matchCount }} 篇</span>
      <button v-if="query" class="clear" title="清空 (Esc)" @click="query = ''">×</button>
    </div>
    <button
      class="batch"
      type="button"
      :class="{ active: nodes.batchSelecting }"
      :title="nodes.batchSelecting ? '退出批量操作' : '批量操作'"
      @click="toggleBatch"
    >☑</button>
    <button class="new-note" type="button" title="新建笔记" @click="createNote">＋</button>
  </div>
</template>

<style scoped>
.title-search-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.title-search {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 9px;
  border: 1px solid var(--c-border);
  border-radius: 999px;
  background: var(--c-bg);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}
.title-search:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--c-border));
}
/* 聚焦：描边 + 一圈柔光，输入面提亮一档 */
.title-search:focus-within {
  border-color: var(--accent);
  background: var(--c-surface);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 14%, transparent);
}
.new-note {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: 17px;
  line-height: 1;
}
.batch {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: 14px;
  line-height: 1;
}
.batch:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--c-border));
  color: var(--c-text);
}
.batch.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.new-note:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--c-border));
  color: var(--accent);
}
.title-search .ico {
  flex: 0 0 auto;
  opacity: 0.65;
  font-size: 12px;
  transition: opacity 0.15s ease;
}
.title-search:focus-within .ico {
  opacity: 1;
}
.input {
  flex: 1;
  min-width: 0;
  /* 覆盖全局 .input 的 6px 纵向内边距：这里要的是一个薄字段，不是表单控件 */
  padding: 0;
  height: 18px;
  border: none;
  background: transparent;
  color: var(--c-text);
  font-size: 12.5px;
}
.input::placeholder {
  color: var(--c-text-3);
}
.input:focus {
  outline: none;
}
/* 命中计数：筛选生效期间的即时反馈 */
.count {
  flex: 0 0 auto;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 10.5px;
  line-height: 16px;
}
.clear {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: var(--c-text-3);
  font-size: 13px;
  line-height: 1;
}
.clear:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
</style>
