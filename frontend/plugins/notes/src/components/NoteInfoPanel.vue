<script setup lang="ts">
import { computed } from 'vue'
import { formatRelative } from '@/core/utils'
import type { NoteDetail } from '@/api/types'

const props = defineProps<{ doc: NoteDetail }>()
const created = computed(() => new Date(props.doc.createdAt).toLocaleString())
const updated = computed(() => formatRelative(props.doc.updatedAt))
</script>

<template>
  <aside class="panel">
    <div class="panel-body">
      <dl class="info">
        <dt>类型</dt>
        <dd>{{ doc.type }}</dd>
        <dt>创建</dt>
        <dd>{{ created }}</dd>
        <dt>更新</dt>
        <dd>{{ updated }}</dd>
        <dt>笔记本</dt>
        <dd>{{ doc.notebooks.map((notebook) => notebook.title).join('、') || '—' }}</dd>
      </dl>
    </div>
  </aside>
</template>

<style scoped>
.panel {
  width: var(--panel-w);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--c-border);
  background: var(--c-surface);
  min-height: 0;
}
.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2);
  min-height: 0;
}
.info {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 4px 8px;
  margin: 0;
  padding: var(--space-2);
  font-size: 12px;
}
.info dt {
  color: var(--c-text-3);
}
.info dd {
  margin: 0;
  word-break: break-all;
}
</style>
