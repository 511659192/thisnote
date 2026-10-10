<script setup lang="ts">
import { computed } from 'vue'
import { formatRelative } from '@/core/utils'
import type { NoteDetail } from '@/api/types'

const props = defineProps<{ doc: NoteDetail }>()
const emit = defineEmits<{
  (e: 'create', title: string): void
}>()

const mentions = computed(() => props.doc.unlinkedMentions ?? [])
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
        <dd>{{ doc.notebooks.map((c) => c.title).join('、') || '—' }}</dd>
      </dl>

      <template v-if="mentions.length">
        <div class="divider"></div>
        <div class="small muted section-label">未链接提及</div>
        <button
          v-for="m in mentions"
          :key="m"
          class="mention"
          title="点击创建/打开该笔记"
          @click="emit('create', m)"
        >
          {{ m }}
        </button>
      </template>
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
  font-size: 12.5px;
}
.info dt {
  color: var(--c-text-3);
}
.info dd {
  margin: 0;
  word-break: break-all;
}
.section-label {
  padding: var(--space-1) var(--space-2);
  font-weight: 600;
}
.mention {
  display: block;
  width: 100%;
  text-align: left;
  padding: 4px 9px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  color: var(--warning);
}
.mention:hover {
  background: var(--c-surface-3);
}
</style>
