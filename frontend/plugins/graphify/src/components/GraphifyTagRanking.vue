<script setup lang="ts">
import { computed } from 'vue'
import type { GraphifyTagItem } from '../tagFilter'

interface RankingItem extends GraphifyTagItem {
  rank: string
  top: boolean
  metricValue: number
  width: number
}

const props = defineProps<{
  tags: GraphifyTagItem[]
  metric: 'edges' | 'occurrences'
}>()
const emit = defineEmits<{ (event: 'select', tag: GraphifyTagItem): void }>()

const ranking = computed<{ items: RankingItem[]; hasVariation: boolean }>(() => {
  const tags = [...props.tags]
    .sort((a, b) => b[props.metric] - a[props.metric] || a.label.localeCompare(b.label, 'zh-CN'))
    .slice(0, 50)
  if (!tags.length) return { items: [], hasVariation: false }
  const maxMetric = Math.max(...tags.map((tag) => tag[props.metric]))
  const minMetric = Math.min(...tags.map((tag) => tag[props.metric]))
  return {
    hasVariation: maxMetric !== minMetric,
    items: tags.map((tag, index) => ({
      ...tag,
      rank: String(index + 1).padStart(2, '0'),
      top: index < 3,
      metricValue: tag[props.metric],
      width: Math.max(4, (tag[props.metric] / Math.max(1, maxMetric)) * 100),
    })),
  }
})

function choose(tag: GraphifyTagItem): void {
  emit('select', tag)
}
</script>

<template>
  <div class="tag-ranking">
    <p v-if="!ranking.items.length" class="empty">暂无标签</p>
    <div v-else class="ranking-grid">
      <button
        v-for="item in ranking.items"
        :key="item.label"
        type="button"
        class="ranking-item"
        :class="{ top: ranking.hasVariation && item.top }"
        :title="item.label + '：' + item.edges + ' 条边，出现 ' + item.occurrences + ' 次'"
        @click="choose(item)"
      >
        <span class="rank-index">{{ item.rank }}</span>
        <span class="rank-body">
          <span class="rank-line">
            <span class="rank-label">{{ item.label }}</span>
            <span class="rank-count">
              {{ item.metricValue }} {{ metric === 'edges' ? '边' : '次' }}
            </span>
          </span>
          <span v-if="ranking.hasVariation" class="rank-track">
            <span class="rank-value" :style="{ width: item.width + '%' }"></span>
          </span>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.tag-ranking {
  min-width: 0;
}

.ranking-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 250px), 1fr));
  gap: 0 18px;
  max-height: 360px;
  overflow-y: auto;
  padding-right: 6px;
  scrollbar-width: thin;
}

.ranking-item {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px;
  color: var(--c-text-2);
  text-align: left;
  border-bottom: 1px solid var(--c-border);
  border-radius: 4px;
}

.ranking-item:hover {
  color: var(--c-text);
  background: var(--c-surface-2);
}

.rank-index {
  flex: 0 0 20px;
  padding-top: 1px;
  color: var(--c-text-muted, #8b9098);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.ranking-item.top .rank-index {
  color: var(--accent);
}

.rank-body {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}

.rank-line {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.rank-label {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.rank-count {
  flex: 0 0 auto;
  color: var(--c-text-muted, #8b9098);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.rank-track {
  height: 2px;
  overflow: hidden;
  background: var(--c-surface-3);
  border-radius: 2px;
}

.rank-value {
  display: block;
  height: 100%;
  background: var(--accent);
  opacity: 0.55;
}

.empty {
  margin: 0;
  padding: 28px 0;
  color: var(--c-text-muted, #8b9098);
  text-align: center;
  font-size: 12px;
}
</style>
