<script setup lang="ts">
import { computed, ref } from 'vue'
import { memoryCycle } from '../stores/memoryCycle'
import { formatInterval } from '../api/memoryCycleApi'

/** 卡片右上角的记忆周期开关：红色表示开启，灰色表示未开启。 */
const props = defineProps<{ cardId: string }>()

const card = computed(() => memoryCycle.stateOf(props.cardId))
const enabled = computed(() => card.value.enabled)
const nextReminder = computed(() => {
  if (!enabled.value) return ''
  const dueAt = card.value.schedule.dueAt
  if (!dueAt) return '未安排'
  if (dueAt <= Date.now()) return '已到期'
  return `下次 ${formatInterval(dueAt)}后`
})
const busy = ref(false)

async function toggle() {
  if (busy.value) return
  busy.value = true
  try {
    await memoryCycle.toggle(props.cardId)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    class="mc-chip"
    :class="{ 'mc-on': enabled, 'mc-idle-chip': !enabled }"
    :title="enabled ? (nextReminder === '已到期' ? '' : nextReminder) : '开启记忆周期'"
    :aria-label="enabled ? `记忆周期已开启，${nextReminder}` : '记忆周期未开启，点击开启'"
    :disabled="busy"
    @pointerdown.stop
    @click.stop="toggle"
  >
    <svg class="mc-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M12 4.5C10.8 3 8.7 2.8 7.3 4.1c-.6.6-.9 1.4-1 2.2-1.5.2-2.6 1.5-2.6 3 0 .7.2 1.3.6 1.8-.8.6-1.3 1.5-1.3 2.5 0 1.4.9 2.6 2.2 3-.1.3-.2.6-.2.9 0 1.7 1.4 3.1 3.1 3.1 1.4 0 2.6-.9 3-2.2L12 17V4.5Z" />
      <path d="M12 4.5C13.2 3 15.3 2.8 16.7 4.1c.6.6.9 1.4 1 2.2 1.5.2 2.6 1.5 2.6 3 0 .7-.2 1.3-.6 1.8.8.6 1.3 1.5 1.3 2.5 0 1.4-.9 2.6-2.2 3 .1.3.2.6.2.9 0 1.7-1.4 3.1-3.1 3.1-1.4 0-2.6-.9-3-2.2L12 17V4.5Z" />
      <path d="M7.5 7.4c1.4 0 2.3 1 2.3 2.3M6.3 12c1.8-.2 3 1 3 2.5m8.2-7.1c-1.4 0-2.3 1-2.3 2.3m3.5 2.3c-1.8-.2-3 1-3 2.5M12 4.5v14" />
    </svg>
    <span v-if="enabled && nextReminder !== '已到期'" class="mc-next-reminder">{{ nextReminder }}</span>
  </button>
</template>

<style scoped>
.mc-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 22px;
  height: 22px;
  padding: 0 4px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
}
.mc-chip.mc-idle-chip {
  color: var(--c-text-3);
}
.mc-chip.mc-on {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 9%, transparent);
}
.mc-chip:hover {
  background: color-mix(in srgb, currentColor 12%, transparent);
}
.mc-chip:disabled {
  cursor: wait;
  opacity: 0.65;
}
.mc-next-reminder {
  color: var(--c-text-2);
  font-size: 10px;
  white-space: nowrap;
}
.mc-icon {
  flex: 0 0 auto;
  display: block;
  width: 17px;
  height: 17px;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
