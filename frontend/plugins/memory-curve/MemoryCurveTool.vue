<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { memoryCurve } from './index'
import type { Grade } from './memoryCurveApi'

/**
 * 记忆曲线的卡片徽标（右上角）。
 *
 * 未开启：只显示一个 🧠 占位按钮，随工具行淡入淡出（悬停可见）。
 * 已开启：徽标**常驻可见**，直接显示状态——「已到期」高亮 /「N天后」倒计时——
 * 这就是开启与未开启在卡片上肉眼可见的差异。点击打开复习浮层。
 */
const props = defineProps<{ cardId: string }>()

const card = computed(() => memoryCurve.stateOf(props.cardId))
const open = ref(false)
const busy = ref(false)
const pos = ref({ top: 0, left: 0 })

const GRADES: { grade: Grade; label: string; hint: string }[] = [
  { grade: 1, label: '忘记', hint: '重置进度，稍后重来' },
  { grade: 2, label: '困难', hint: '明天再见，曲线变陡' },
  { grade: 3, label: '良好', hint: '明天再见' },
  { grade: 4, label: '简单', hint: '明天再见，曲线变缓' },
]

const schedule = computed(() => card.value.schedule)
const enabled = computed(() => card.value.enabled)
const due = computed(() => enabled.value && schedule.value.due)

const stateLabel = computed(() => {
  switch (schedule.value.state) {
    case 'review': return '复习'
    case 'learning': return '学习中'
    case 'relearning': return '重学中'
    default: return '新卡片'
  }
})

/** 徽标文案：已到期 / 倒计时 / 新卡片。 */
const chipLabel = computed(() => {
  if (!schedule.value.cardId) return '未复习'
  if (due.value) return '已到期'
  const days = schedule.value.intervalDays
  if (days >= 1) return `${Math.round(days)}天后`
  const minutes = Math.max(1, Math.round((schedule.value.dueAt - Date.now()) / 60_000))
  return `${minutes}分钟后`
})

/** 遗忘曲线：R(t)=e^(-t/S)，S 取当前间隔（天），画到到期日为止。 */
const curvePoints = computed(() => {
  const interval = Math.max(schedule.value.intervalDays, 0.5)
  const samples = 40
  const pts: string[] = []
  for (let i = 0; i <= samples; i++) {
    const t = (i / samples) * interval
    const r = Math.exp(-t / interval)
    const x = 8 + (i / samples) * 124
    const y = 8 + (1 - r) * 44
    pts.push(`${x.toFixed(1)},${y.toFixed(1)}`)
  }
  return pts.join(' ')
})

async function onChipClick(e: MouseEvent) {
  if (!enabled.value) {
    busy.value = true
    try {
      await memoryCurve.toggle(props.cardId)
    } finally {
      busy.value = false
    }
    return
  }
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  pos.value = {
    top: Math.min(r.bottom + 6, window.innerHeight - 330),
    left: Math.max(8, Math.min(r.right - 220, window.innerWidth - 236)),
  }
  open.value = !open.value
}

async function grade(g: Grade) {
  busy.value = true
  try {
    await memoryCurve.review(props.cardId, g)
    open.value = false
  } finally {
    busy.value = false
  }
}

async function disable() {
  busy.value = true
  try {
    await memoryCurve.toggle(props.cardId)
    open.value = false
  } finally {
    busy.value = false
  }
}

// 卡片被删除时收起浮层，避免悬空。
watch(() => props.cardId, () => { open.value = false })
</script>

<template>
  <div class="mc-root">
    <button
      class="mc-chip"
      :class="{ 'mc-on': enabled, 'mc-due': due, 'mc-idle-chip': !enabled }"
      :title="enabled ? `记忆曲线 · ${stateLabel} · ${chipLabel}` : '为这张卡片开启记忆曲线'"
      @pointerdown.stop
      @click.stop="onChipClick"
    >
      🧠 <span class="mc-chip-text">{{ chipLabel }}</span>
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        class="mc-pop"
        :style="{ zIndex: 10000, top: `${pos.top}px`, left: `${pos.left}px` }"
        @pointerdown.stop
        @click.stop
      >
        <div class="mc-head">
          <span class="mc-title">记忆曲线 · {{ stateLabel }}</span>
          <button class="mc-close" title="关闭" @click="open = false">✕</button>
        </div>

        <div class="mc-dued">{{ chipLabel }}</div>

        <svg class="mc-curve" viewBox="0 0 140 60" aria-hidden="true">
          <polyline :points="curvePoints" fill="none" stroke="var(--accent)" stroke-width="1.5" />
          <line x1="8" y1="52" x2="132" y2="52" class="mc-axis" />
          <text x="10" y="12" class="mc-axis-text">记忆保持率</text>
          <text x="104" y="48" class="mc-axis-text">{{ Math.max(1, Math.round(schedule.intervalDays)) }}天</text>
        </svg>

        <dl class="mc-stats">
          <div><dt>间隔</dt><dd>{{ Math.max(0, Math.round(schedule.intervalDays * 10) / 10) }} 天</dd></div>
          <div><dt>复习</dt><dd>{{ schedule.reps }} 次</dd></div>
          <div><dt>遗忘</dt><dd>{{ schedule.lapses }} 次</dd></div>
          <div><dt>ease</dt><dd>{{ Math.round(schedule.ease * 100) / 100 }}</dd></div>
        </dl>

        <div class="mc-grades">
          <button
            v-for="g in GRADES"
            :key="g.grade"
            class="mc-grade"
            :disabled="busy"
            :title="g.hint"
            @click="grade(g.grade)"
          >
            {{ g.label }}
          </button>
        </div>

        <button class="mc-off" :disabled="busy" @click="disable">关闭这张卡片的记忆曲线</button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.mc-root {
  display: contents;
}
.mc-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1;
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  color: var(--c-text-3);
  cursor: pointer;
  white-space: nowrap;
}
/* 未开启：一个安静的 🧠 占位，随工具行淡入淡出（不常驻，避免满屏噪音）。 */
.mc-chip.mc-idle-chip {
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .mc-chip.mc-idle-chip,
.card.selected .mc-chip.mc-idle-chip,
.card.focused .mc-chip.mc-idle-chip,
.card.editing .mc-chip.mc-idle-chip {
  opacity: 1;
}
/* 已开启：常驻徽标——这就是肉眼可见的差异。到期时用品牌色强调。 */
.mc-chip.mc-on {
  opacity: 1;
  color: var(--c-text);
}
.mc-chip.mc-on.mc-due {
  background: color-mix(in srgb, var(--accent) 18%, var(--c-surface));
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  color: var(--accent);
  font-weight: 600;
}
.mc-chip.mc-on:not(.mc-due) {
  background: color-mix(in srgb, var(--accent) 8%, var(--c-surface));
  border-color: color-mix(in srgb, var(--accent) 30%, transparent);
}
.mc-chip:hover {
  border-color: var(--accent);
}

.mc-pop {
  position: fixed;
  width: 220px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow-lg);
  font-size: 12px;
}
.mc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mc-title {
  font-weight: 600;
}
.mc-close {
  border: none;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
}
.mc-dued {
  margin: 6px 0 4px;
  color: var(--accent);
  font-weight: 600;
}
.mc-curve {
  width: 100%;
  height: 60px;
}
.mc-axis {
  stroke: var(--c-border);
  stroke-width: 1;
}
.mc-axis-text {
  font-size: 7px;
  fill: var(--c-text-3);
}
.mc-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 10px;
  margin: 6px 0;
}
.mc-stats div {
  display: flex;
  justify-content: space-between;
}
.mc-stats dt {
  color: var(--c-text-3);
}
.mc-grades {
  display: flex;
  gap: 4px;
  margin: 6px 0;
}
.mc-grade {
  flex: 1;
  padding: 4px 0;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: var(--c-surface-2);
  color: var(--c-text);
  font-size: 12px;
  cursor: pointer;
}
.mc-grade:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.mc-off {
  width: 100%;
  padding: 4px 0;
  border: none;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  cursor: pointer;
  text-decoration: underline;
}
.mc-off:hover {
  color: var(--c-text);
}
</style>
