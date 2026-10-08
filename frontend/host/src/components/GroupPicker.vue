<script setup lang="ts">
import type { Notebook } from '@/api/types'

/**
 * 分组选择器：把一篇笔记移到哪个分组（单归属）。
 *
 * <p>用「可见的分组行」当列表（来自 store 的 visibleGroups），所以它跟侧栏看到的结构完全一致
 * ——缩进即层级，展开状态也一致。最后一项「＋ 新建分组」直接就地建，省得为了分组来回跳。
 */
defineProps<{
  open: boolean
  rows: { group: Notebook; depth: number }[]
  title: string
}>()

const emit = defineEmits<{
  (e: 'pick', notebookId: string | null): void
  (e: 'create'): void
  (e: 'close'): void
}>()
</script>

<template>
  <div v-if="open" class="gp-mask" @click.self="emit('close')">
    <div class="gp">
      <header class="gp-head">{{ title }}</header>

      <div class="gp-list">
        <button v-for="row in rows" :key="row.group.id" class="gp-row" @click="emit('pick', row.group.id)">
          <span class="gp-indent" :style="{ width: row.depth * 14 + 'px' }"></span>
          <span class="gp-ico">▣</span>
          <span class="gp-title">{{ row.group.title }}</span>
          <span v-if="row.group.count" class="gp-count">{{ row.group.count }}</span>
        </button>

        <p v-if="!rows.length" class="small muted gp-empty">
          还没有分组。先在侧栏「全部笔记 → 新建分组」里建一个，笔记就能分组收纳了。
        </p>

        <button class="gp-row gp-new" @click="emit('create')">
          <span class="gp-indent"></span>
          <span class="gp-ico">＋</span>
          <span class="gp-title">新建分组…</span>
        </button>
      </div>

      <footer class="gp-foot">
        <button class="btn sm ghost" @click="emit('close')">取消</button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.gp-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgb(0 0 0 / 35%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.gp {
  width: 340px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-2, 8px);
  box-shadow: var(--shadow-2, 0 12px 32px rgb(0 0 0 / 25%));
  overflow: hidden;
}
.gp-head {
  padding: 10px var(--space-3);
  border-bottom: 1px solid var(--c-border);
  font-size: 13px;
  font-weight: 600;
}
.gp-list {
  overflow-y: auto;
  padding: var(--space-2);
}
.gp-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--c-text);
  text-align: left;
  cursor: pointer;
}
.gp-row:hover {
  background: var(--c-surface-2);
}
.gp-indent {
  flex: 0 0 auto;
}
.gp-ico {
  opacity: 0.6;
  font-size: 11px;
}
.gp-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gp-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--c-text-3);
}
.gp-new {
  color: var(--c-text-3);
  border-top: 1px solid var(--c-border);
  border-radius: 0;
  margin-top: var(--space-1);
}
.gp-empty {
  padding: var(--space-2);
}
.gp-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--c-border);
}
</style>
