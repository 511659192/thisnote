<script setup lang="ts">
import { computed, ref } from 'vue'
import { useNoteStore } from '@/stores/notes'
import { formatRelative } from '@/core/utils'
import type { NoteDetail } from '@/api/types'

const props = defineProps<{ doc: NoteDetail }>()
const emit = defineEmits<{
  (e: 'open', id: string): void
  (e: 'create', title: string): void
}>()

const nodes = useNoteStore()
const tab = ref<'backlinks' | 'outbound' | 'info'>('backlinks')

const mentions = computed(() => props.doc.unlinkedMentions ?? [])

const created = computed(() => new Date(props.doc.createdAt).toLocaleString())
const updated = computed(() => formatRelative(props.doc.updatedAt))
</script>

<template>
  <aside class="panel">
    <div class="tabs">
      <button :class="{ active: tab === 'backlinks' }" @click="tab = 'backlinks'">
        反向链接 <span class="n">{{ doc.backlinks.length }}</span>
      </button>
      <button :class="{ active: tab === 'outbound' }" @click="tab = 'outbound'">
        出链 <span class="n">{{ doc.outbound.length }}</span>
      </button>
      <button :class="{ active: tab === 'info' }" @click="tab = 'info'">信息</button>
    </div>

    <div class="panel-body">
      <template v-if="tab === 'backlinks'">
        <div v-if="!doc.backlinks.length" class="small muted pad">还没有笔记引用它。</div>
        <button
          v-for="b in doc.backlinks"
          :key="b.noteId + (b.label ?? '')"
          class="backlink"
          @click="emit('open', b.noteId)"
        >
          <span class="bl-title">{{ b.title }}</span>
          <span v-if="b.context" class="bl-context small muted">{{ b.context }}</span>
        </button>
      </template>

      <template v-else-if="tab === 'outbound'">
        <div v-if="!doc.outbound.length" class="small muted pad">
          没有出链。正文里输入 <code>[[</code> 可链接其他笔记。
        </div>
        <div v-for="l in doc.outbound" :key="l.id" class="outbound">
          <button v-if="l.resolved && l.toNodeId" class="link-btn" @click="emit('open', l.toNodeId)">
            [[{{ l.label || l.toTitle }}]]
          </button>
          <button v-else class="link-btn unresolved" @click="emit('create', l.toTitle || '')">
            [[{{ l.toTitle }}]] <span class="small">未创建，点击新建</span>
          </button>
        </div>
      </template>

      <template v-else>
        <dl class="info">
          <dt>类型</dt>
          <dd>{{ doc.type }}</dd>
          <dt>创建</dt>
          <dd>{{ created }}</dd>
          <dt>更新</dt>
          <dd>{{ updated }}</dd>
          <dt v-if="doc.source">来源</dt>
          <dd v-if="doc.source">{{ doc.source }}</dd>
          <dt>状态</dt>
          <dd>{{ doc.status || '—' }}</dd>
          <dt>笔记本</dt>
          <dd>{{ doc.notebooks.map((c) => c.title).join('、') || '—' }}</dd>
          <dt>别名</dt>
          <dd>{{ doc.aliases.join('、') || '—' }}</dd>
        </dl>
      </template>

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
.tabs {
  display: flex;
  border-bottom: 1px solid var(--c-border);
}
.tabs button {
  flex: 1;
  padding: 8px 4px;
  font-size: 12.5px;
  color: var(--c-text-3);
  border-bottom: 2px solid transparent;
}
.tabs button.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}
.tabs .n {
  font-size: 10.5px;
  opacity: 0.7;
}
.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2);
  min-height: 0;
}
.pad {
  padding: var(--space-2);
}
.backlink {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 7px 9px;
  border-radius: var(--radius);
}
.backlink:hover {
  background: var(--c-surface-3);
}
.bl-title {
  font-size: 13px;
  font-weight: 500;
}
.bl-context {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.outbound {
  padding: 3px 0;
}
.link-btn {
  text-align: left;
  color: var(--accent);
  font-size: 12.5px;
  padding: 3px 6px;
  border-radius: var(--radius-sm);
}
.link-btn:hover {
  background: var(--c-surface-3);
}
.link-btn.unresolved {
  color: var(--warning);
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
