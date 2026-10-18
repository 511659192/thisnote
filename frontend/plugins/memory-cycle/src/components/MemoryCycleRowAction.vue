<script setup lang="ts">
import { computed, ref } from 'vue'
import { emitPluginStateChange, type NoteRef } from '@thisnode/plugin-api'
import { memoryCycleApi } from '../api/memoryCycleApi'
import { memoryCycle } from '../stores/memoryCycle'
import MemoryCycleIcon from './MemoryCycleIcon.vue'

/** 笔记列表行内的记忆曲线开关：与卡片右上角的 🧠 同一图标、同一份状态。 */
const props = defineProps<{ note: NoteRef }>()

const cardIds = computed(() => props.note.cardIds ?? [])
const enabledCount = computed(
  () => cardIds.value.filter((cardId) => memoryCycle.stateOf(cardId).enabled).length,
)
const allEnabled = computed(() => cardIds.value.length > 0 && enabledCount.value === cardIds.value.length)
/** 部分开启：有卡片已开启但未全部开启。 */
const partial = computed(() => enabledCount.value > 0 && !allEnabled.value)
const busy = ref(false)

const title = computed(() => {
  if (allEnabled.value) return '关闭记忆曲线'
  if (partial.value) return `部分卡片已开启（${enabledCount.value}/${cardIds.value.length}），点击全部开启`
  return '开启记忆曲线'
})

async function toggle() {
  if (!cardIds.value.length || busy.value) return
  busy.value = true
  try {
    const value = !allEnabled.value
    await memoryCycleApi.setStates(cardIds.value, value)
    await memoryCycle.loadState(cardIds.value)
    emitPluginStateChange({ pluginId: 'memory-cycle', cardIds: cardIds.value })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    type="button"
    class="row-mc"
    :class="{ 'row-mc-on': allEnabled, 'row-mc-partial': partial }"
    :title="title"
    :disabled="busy"
    @pointerdown.stop
    @click.stop="toggle"
  >
    <MemoryCycleIcon />
  </button>
</template>

<style scoped>
.row-mc {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-text-3);
  opacity: 0.55;
  cursor: pointer;
}
.row-mc :deep(.mc-icon) {
  width: 16px;
  height: 16px;
}
.row-mc:hover {
  background: color-mix(in srgb, currentColor 12%, transparent);
  color: var(--c-text-2);
  opacity: 1;
}
/* 全部开启：浅红；部分开启：琥珀 */
.row-mc.row-mc-on {
  color: color-mix(in srgb, var(--danger) 45%, var(--c-text-3));
  opacity: 1;
}
.row-mc.row-mc-partial {
  color: color-mix(in srgb, var(--accent) 55%, var(--c-text-3));
  opacity: 1;
}
.row-mc:disabled {
  cursor: wait;
  opacity: 0.65;
}
</style>
